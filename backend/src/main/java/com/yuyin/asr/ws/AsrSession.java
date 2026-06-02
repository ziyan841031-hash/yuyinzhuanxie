package com.yuyin.asr.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyin.asr.aggregate.Aggregator;
import com.yuyin.asr.aggregate.Sentence;
import com.yuyin.asr.config.AsrProperties;
import com.yuyin.asr.dashscope.DashScopeClient;
import com.yuyin.asr.delivery.AnalysisDeliveryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次收音会话：桥接 前端WS &lt;-&gt; DashScope &lt;-&gt; 聚合器/投递。
 *
 * M4 稳定性：
 *  - 代理↔DashScope 断线自动重连（退避），重连间隙音频缓冲后补发，聚合缓冲不丢；
 *  - 滚动会话：接近单任务时长上限前，预启动新任务并在其就绪后无缝切换（overlap）；
 *  - 致命错误（如缺 Key / 超过重连上限）才上报前端并结束。
 */
public class AsrSession {

    private static final Logger log = LoggerFactory.getLogger(AsrSession.class);

    private final String sessionId;
    private final WebSocketSession client;
    private final ObjectMapper mapper;
    private final AsrProperties props;
    private volatile AsrProperties.DashScope dsCfg;   // start 时可被按会话覆盖
    private final ScheduledExecutorService scheduler;
    private final AnalysisDeliveryClient delivery;

    private final Object sendLock = new Object();
    private final Object stateLock = new Object();
    private final AtomicLong sentenceSeq = new AtomicLong(0);

    // 重连/切换间隙的音频缓冲
    private final Object audioLock = new Object();
    private final Deque<ByteBuffer> audioBuffer = new ArrayDeque<>();

    private volatile Aggregator aggregator;
    private volatile DashScopeClient current;     // 当前生效的识别任务
    private volatile DashScopeClient pendingNext;  // 滚动切换中预启动的下一个任务

    private volatile boolean active = false;       // start 之后、结束之前
    private volatile boolean stopping = false;     // 收到 stop / 正在收尾
    private volatile boolean started = false;      // current 已就绪，可直接送音频
    private volatile boolean everReady = false;    // 是否已通知过前端 ready
    private volatile boolean ended = false;        // 是否已结束（防重复 done）
    private int reconnectAttempts = 0;

    private ScheduledFuture<?> rotateHandle;
    private ScheduledFuture<?> reconnectHandle;

    public AsrSession(String sessionId, WebSocketSession client, ObjectMapper mapper,
                      AsrProperties props, ScheduledExecutorService scheduler,
                      AnalysisDeliveryClient delivery) {
        this.sessionId = sessionId;
        this.client = client;
        this.mapper = mapper;
        this.props = props;
        this.dsCfg = props.getDashscope();
        this.scheduler = scheduler;
        this.delivery = delivery;
    }

    public String getSessionId() { return sessionId; }

    // ───── 生命周期 ─────

    public void start(AsrProperties.Aggregator override, AsrProperties.DashScope dsOverride) {
        if (dsOverride != null) this.dsCfg = dsOverride;
        AsrProperties.Aggregator aggCfg = (override != null) ? override : props.getAggregator().copy();
        this.aggregator = new Aggregator(sessionId, aggCfg, scheduler, payload -> {
            delivery.deliver(payload);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "flushed");
            n.put("batchId", payload.batchId);
            n.put("batchSeq", payload.batchSeq);
            n.put("chars", payload.chars);
            n.put("triggeredBy", payload.triggeredBy);
            send(n);
        });
        synchronized (stateLock) {
            active = true;
            current = newClient();
            current.connect();
            scheduleRotate();
        }
        log.info("[{}] start (charThreshold={}, intervalMs={}, silenceFlushMs={}, sessionMaxSeconds={})",
                sessionId, aggCfg.getCharThreshold(), aggCfg.getIntervalMs(), aggCfg.getSilenceFlushMs(),
                dsCfg.getSessionMaxSeconds());
    }

    public void onAudio(ByteBuffer pcm) {
        if (!active) return;
        ByteBuffer copy = copyOf(pcm);          // 必须拷贝：原 buffer 会被复用，且 sendBinary 异步
        DashScopeClient c = current;
        if (started && c != null && c.isStarted()) {
            c.sendAudio(copy);
        } else {
            bufferAudio(copy);                   // 重连/切换间隙先缓冲
        }
    }

    /** 前端 stop：通知 DashScope 收尾。 */
    public void stop() {
        synchronized (stateLock) {
            if (!active) { endSession(); return; }
            stopping = true;
            cancel(rotateHandle);
            cancel(reconnectHandle);
            if (pendingNext != null) { pendingNext.close(); pendingNext = null; }
            DashScopeClient c = current;
            if (c != null) c.finish(); else endSession();
        }
    }

    /** 连接关闭/异常时的清理（聚合器强制 flush，不丢内容）。 */
    public void dispose() {
        synchronized (stateLock) {
            active = false;
            stopping = true;
            cancel(rotateHandle);
            cancel(reconnectHandle);
            Aggregator a = aggregator;
            if (a != null) a.close();
            if (current != null) { current.close(); current = null; }
            if (pendingNext != null) { pendingNext.close(); pendingNext = null; }
            ended = true;
        }
    }

    // ───── DashScope 客户端工厂（每个任务一套带身份校验的回调）─────

    private DashScopeClient newClient() {
        final DashScopeClient[] holder = new DashScopeClient[1];
        DashScopeClient c = new DashScopeClient(dsCfg, mapper, new DashScopeClient.Callbacks() {
            @Override public void onStarted() { onCliStarted(holder[0]); }
            @Override public void onSentence(String t, boolean end, long b, long e) { onCliSentence(holder[0], t, end, b, e); }
            @Override public void onFinished() { onCliFinished(holder[0]); }
            @Override public void onError(String code, String msg) { onCliError(holder[0], code, msg); }
        });
        holder[0] = c;
        return c;
    }

    private void onCliStarted(DashScopeClient c) {
        synchronized (stateLock) {
            if (c == pendingNext) {
                // 滚动切换：新任务就绪，无缝接管，收尾旧任务
                DashScopeClient old = current;
                current = c;
                pendingNext = null;
                started = true;
                reconnectAttempts = 0;
                flushAudio(c);
                if (old != null) old.finish();      // old 的后续回调因 old!=current 被忽略
                scheduleRotate();
                log.info("[{}] rolling switch done (taskId rotated)", sessionId);
            } else if (c == current) {
                started = true;
                reconnectAttempts = 0;
                flushAudio(c);
                if (!everReady) {
                    everReady = true;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "ready");
                    send(m);
                } else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "reconnected");
                    send(m);
                    log.info("[{}] reconnected to DashScope", sessionId);
                }
            }
            // 否则是陈旧客户端，忽略
        }
    }

    private void onCliSentence(DashScopeClient c, String text, boolean sentenceEnd, long beginTime, long endTime) {
        if (c != current) return;                // 忽略被切换掉的旧任务的迟到结果
        if (text == null) text = "";
        if (sentenceEnd) {
            long id = sentenceSeq.incrementAndGet();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "final");
            m.put("sentenceId", id);
            m.put("text", text);
            send(m);
            Aggregator a = aggregator;
            if (a != null) a.onFinalSentence(new Sentence(id, text, beginTime, endTime));
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "partial");
            m.put("text", text);
            m.put("beginTime", beginTime);
            m.put("endTime", endTime);
            send(m);
        }
    }

    private void onCliFinished(DashScopeClient c) {
        synchronized (stateLock) {
            if (c != current) return;            // 旧任务收尾，忽略
            if (stopping) {
                endSession();
            } else {
                // 非预期收尾，尝试重连续上
                scheduleReconnect("TASK_FINISHED_UNEXPECTED");
            }
        }
    }

    private void onCliError(DashScopeClient c, String code, String message) {
        synchronized (stateLock) {
            if (!active || ended) return;
            if (c != current && c != pendingNext) return;     // 陈旧客户端

            if ("NO_API_KEY".equals(code)) {                  // 致命，无法重连
                fatal(code, message);
                return;
            }
            if (c == pendingNext) {
                // 滚动预启动失败：放弃本次切换，保留当前任务，稍后再试
                log.warn("[{}] rolling pre-start failed ({}), keep current and reschedule", sessionId, code);
                pendingNext = null;
                scheduleRotate();
                return;
            }
            // 当前任务断了 → 退避重连
            started = false;
            scheduleReconnect(code);
        }
    }

    // ───── 重连 / 滚动 ─────

    private void scheduleReconnect(String reason) {
        if (!active || stopping) return;
        reconnectAttempts++;
        if (reconnectAttempts > dsCfg.getMaxReconnectAttempts()) {
            fatal("RECONNECT_FAILED", "reason=" + reason + " attempts=" + reconnectAttempts);
            return;
        }
        long delay = Math.min(dsCfg.getReconnectMaxMs(),
                dsCfg.getReconnectBaseMs() * (1L << Math.min(20, reconnectAttempts - 1)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "reconnecting");
        m.put("attempt", reconnectAttempts);
        send(m);
        log.warn("[{}] DashScope lost ({}), reconnect #{} in {}ms", sessionId, reason, reconnectAttempts, delay);
        reconnectHandle = scheduler.schedule(() -> {
            synchronized (stateLock) {
                if (!active || stopping) return;
                started = false;
                if (current != null) current.close();   // 关闭已失效的旧连接，防悬挂
                current = newClient();
                current.connect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void scheduleRotate() {
        long sec = dsCfg.getSessionMaxSeconds();
        if (sec <= 0) return;
        cancel(rotateHandle);
        rotateHandle = scheduler.schedule(this::rotate, sec, TimeUnit.SECONDS);
    }

    private void rotate() {
        synchronized (stateLock) {
            if (!active || stopping || pendingNext != null) return;
            log.info("[{}] rolling session: pre-starting next task before time limit", sessionId);
            pendingNext = newClient();
            pendingNext.connect();   // 就绪后在 onCliStarted 里无缝切换
        }
    }

    // ───── 结束 ─────

    private void endSession() {
        endSession(false);
    }

    /** fatal=true 表示已上报致命错误，则不再发 done（避免前端 error 态被覆盖为 idle）。 */
    private void endSession(boolean fatal) {
        if (ended) return;
        ended = true;
        cancel(rotateHandle);
        cancel(reconnectHandle);
        Aggregator a = aggregator;
        if (a != null) a.close();    // 强制 flush 残余
        if (!fatal) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "done");
            send(m);
        }
        if (current != null) { current.close(); current = null; }
        if (pendingNext != null) { pendingNext.close(); pendingNext = null; }
    }

    private void fatal(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", code);
        m.put("message", message);
        send(m);
        active = false;
        endSession(true);
    }

    // ───── 音频缓冲 ─────

    private void bufferAudio(ByteBuffer buf) {
        synchronized (audioLock) {
            int cap = Math.max(1, dsCfg.getAudioBufferFrames());
            while (audioBuffer.size() >= cap) {
                audioBuffer.pollFirst();             // 缓冲满：丢最旧帧，保最新
            }
            audioBuffer.addLast(buf);
        }
    }

    private void flushAudio(DashScopeClient c) {
        synchronized (audioLock) {
            ByteBuffer f;
            while ((f = audioBuffer.pollFirst()) != null) {
                c.sendAudio(f);
            }
        }
    }

    private static ByteBuffer copyOf(ByteBuffer src) {
        ByteBuffer dup = src.duplicate();
        byte[] data = new byte[dup.remaining()];
        dup.get(data);
        return ByteBuffer.wrap(data);
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    // ───── 发送给前端（线程安全）─────

    private void send(Map<String, Object> msg) {
        if (!client.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (sendLock) {
                client.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.warn("[{}] send to client failed: {}", sessionId, e.toString());
        }
    }
}

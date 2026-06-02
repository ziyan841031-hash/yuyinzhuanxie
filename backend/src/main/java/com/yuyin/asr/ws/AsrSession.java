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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次收音会话：桥接 前端WS <-> DashScope <-> 聚合器/投递。
 */
public class AsrSession implements DashScopeClient.Callbacks {

    private static final Logger log = LoggerFactory.getLogger(AsrSession.class);

    private final String sessionId;
    private final WebSocketSession client;
    private final ObjectMapper mapper;
    private final AsrProperties props;
    private final ScheduledExecutorService scheduler;
    private final AnalysisDeliveryClient delivery;

    private final Object sendLock = new Object();
    private final AtomicLong sentenceSeq = new AtomicLong(0);

    private volatile DashScopeClient ds;
    private volatile Aggregator aggregator;
    private volatile boolean stopping = false;

    public AsrSession(String sessionId, WebSocketSession client, ObjectMapper mapper,
                      AsrProperties props, ScheduledExecutorService scheduler,
                      AnalysisDeliveryClient delivery) {
        this.sessionId = sessionId;
        this.client = client;
        this.mapper = mapper;
        this.props = props;
        this.scheduler = scheduler;
        this.delivery = delivery;
    }

    public String getSessionId() { return sessionId; }

    /** 处理前端 start：可携带聚合触发器覆盖配置。 */
    public void start(AsrProperties.Aggregator override) {
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
        this.ds = new DashScopeClient(props.getDashscope(), mapper, this);
        ds.connect();
        log.info("[{}] session start (charThreshold={}, intervalMs={}, silenceFlushMs={})",
                sessionId, aggCfg.getCharThreshold(), aggCfg.getIntervalMs(), aggCfg.getSilenceFlushMs());
    }

    public void onAudio(ByteBuffer pcm) {
        DashScopeClient d = ds;
        if (d != null) d.sendAudio(pcm);
    }

    /** 前端 stop：通知 DashScope 收尾，聚合器在 onFinished 里强制 flush。 */
    public void stop() {
        stopping = true;
        DashScopeClient d = ds;
        if (d != null) d.finish();
    }

    /** 连接关闭/异常时的清理。 */
    public void dispose() {
        Aggregator a = aggregator;
        if (a != null) a.close();
        DashScopeClient d = ds;
        if (d != null) d.close();
    }

    // ───── DashScopeClient.Callbacks ─────

    @Override
    public void onStarted() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ready");
        send(m);
    }

    @Override
    public void onSentence(String text, boolean sentenceEnd, long beginTime, long endTime) {
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

    @Override
    public void onFinished() {
        Aggregator a = aggregator;
        if (a != null) a.close();   // 强制 flush 残余
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "done");
        send(m);
    }

    @Override
    public void onError(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", code);
        m.put("message", message);
        send(m);
        if (!stopping) dispose();
    }

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

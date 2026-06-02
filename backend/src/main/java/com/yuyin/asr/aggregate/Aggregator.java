package com.yuyin.asr.aggregate;

import com.yuyin.asr.config.AsrProperties;
import com.yuyin.asr.delivery.BatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 内容聚合器：攒定稿句，按可配置触发器成批输出。
 *
 * 触发器（任一命中即 flush，<=0 表示关闭）：
 *  - charThreshold      累计字数
 *  - sentenceThreshold  累计句数
 *  - intervalMs         周期上报
 *  - silenceFlushMs     说话停顿（距上次定稿句的间隔）
 *  - maxBufferMs        最大滞留兜底（强制，忽略 minChars）
 *  - 结束强制 flush（close）
 *
 * 完整性保证：只接收 sentence_end 的定稿句；批间携带 overlap 上下文。
 * 线程安全：onFinalSentence 与定时 tick 共用 lock 串行化。
 */
public class Aggregator {

    private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

    private final String sessionId;
    private final AsrProperties.Aggregator cfg;
    private final Consumer<BatchPayload> sink;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final List<Sentence> buffer = new ArrayList<>();
    private final Deque<Sentence> overlap = new ArrayDeque<>();
    private int bufferChars = 0;
    private long batchSeq = 0;
    private long bufferStartedAt = 0;
    private long lastSentenceAt = 0;
    private long lastFlushAt = System.currentTimeMillis();
    private boolean closed = false;

    private final ScheduledFuture<?> tickHandle;

    public Aggregator(String sessionId, AsrProperties.Aggregator cfg,
                      ScheduledExecutorService scheduler, Consumer<BatchPayload> sink) {
        this.sessionId = sessionId;
        this.cfg = cfg;
        this.scheduler = scheduler;
        this.sink = sink;
        // 250ms 一拍，驱动 interval / silence / maxBuffer 三类时间触发器
        this.tickHandle = scheduler.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** 收到一条定稿句。 */
    public void onFinalSentence(Sentence s) {
        synchronized (lock) {
            if (closed) return;
            long now = System.currentTimeMillis();
            if (buffer.isEmpty()) bufferStartedAt = now;
            buffer.add(s);
            bufferChars += s.getText().length();
            lastSentenceAt = now;

            if (cfg.getCharThreshold() > 0 && bufferChars >= cfg.getCharThreshold()) {
                flush("charThreshold", false);
            } else if (cfg.getSentenceThreshold() > 0 && buffer.size() >= cfg.getSentenceThreshold()) {
                flush("sentenceThreshold", false);
            }
        }
    }

    private void tick() {
        synchronized (lock) {
            if (closed || buffer.isEmpty()) return;
            long now = System.currentTimeMillis();

            if (cfg.getMaxBufferMs() > 0 && now - bufferStartedAt >= cfg.getMaxBufferMs()) {
                flush("maxBuffer", true);          // 兜底：强制，忽略 minChars
                return;
            }
            if (cfg.getSilenceFlushMs() > 0 && now - lastSentenceAt >= cfg.getSilenceFlushMs()) {
                flush("silence", false);
                return;
            }
            if (cfg.getIntervalMs() > 0 && now - lastFlushAt >= cfg.getIntervalMs()) {
                flush("interval", false);
            }
        }
    }

    /** 结束：强制输出残余，停止定时器。 */
    public void close() {
        synchronized (lock) {
            if (closed) return;
            if (!buffer.isEmpty()) flush("final", true, true);
            closed = true;
        }
        tickHandle.cancel(false);
    }

    private void flush(String reason, boolean force) {
        flush(reason, force, false);
    }

    /** 调用方需持有 lock。 */
    private void flush(String reason, boolean force, boolean isFinal) {
        if (buffer.isEmpty()) return;
        if (!force && bufferChars < cfg.getMinCharsToFlush()) {
            log.debug("[{}] skip flush({}): chars={} < min={}", sessionId, reason, bufferChars, cfg.getMinCharsToFlush());
            return;
        }

        BatchPayload p = new BatchPayload();
        p.sessionId = sessionId;
        p.batchId = "b_" + sessionId + "_" + (++batchSeq);
        p.batchSeq = batchSeq;
        p.isFinal = isFinal;
        p.triggeredBy = reason;
        p.chars = bufferChars;
        for (Sentence s : overlap) p.context.add(new BatchPayload.Item(s));

        StringBuilder text = new StringBuilder();
        for (Sentence s : buffer) {
            p.sentences.add(new BatchPayload.Item(s));
            text.append(s.getText());
        }
        p.text = text.toString();

        // 更新 overlap = 本批末尾 N 句
        overlap.clear();
        int keep = Math.max(0, cfg.getOverlapSentences());
        for (int i = Math.max(0, buffer.size() - keep); i < buffer.size(); i++) {
            overlap.addLast(buffer.get(i));
        }

        log.info("[{}] flush seq={} by={} chars={} sentences={} final={}",
                sessionId, batchSeq, reason, bufferChars, buffer.size(), isFinal);

        buffer.clear();
        bufferChars = 0;
        bufferStartedAt = 0;
        lastFlushAt = System.currentTimeMillis();

        try {
            sink.accept(p);
        } catch (Exception e) {
            log.error("[{}] sink error on batch {}", sessionId, p.batchId, e);
        }
    }
}

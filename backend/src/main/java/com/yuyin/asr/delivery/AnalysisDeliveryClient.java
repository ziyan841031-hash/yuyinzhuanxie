package com.yuyin.asr.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyin.asr.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把聚合批 POST 给分析后端（占位）。
 * 可靠性：独立线程池异步；内存退避快重试；仍失败则落盘队列(DeliveryQueue)做持久化续传。
 * 幂等键 X-Batch-Id，重试/续传都安全。
 */
@Component
public class AnalysisDeliveryClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDeliveryClient.class);

    private final AsrProperties.Delivery cfg;
    private final ObjectMapper mapper;
    private final DeliveryQueue queue;
    private final HttpClient http;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "asr-delivery");
        t.setDaemon(true);
        return t;
    });

    public AnalysisDeliveryClient(AsrProperties props, ObjectMapper mapper, DeliveryQueue queue) {
        this.cfg = props.getDelivery();
        this.mapper = mapper;
        this.queue = queue;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        // 磁盘队列重投复用同一条 POST 逻辑
        this.queue.setSender(this::postOnce);
    }

    /** 异步投递：内存快重试 N 次，仍失败落盘持久化（绝不丢）。 */
    public void deliver(BatchPayload payload) {
        pool.submit(() -> {
            for (int attempt = 0; attempt <= cfg.getMaxRetries(); attempt++) {
                if (postOnce(payload)) {
                    log.debug("delivered batch {} (attempt {})", payload.batchId, attempt + 1);
                    return;
                }
                if (attempt < cfg.getMaxRetries()) {
                    sleep(cfg.getRetryBaseMs() * (1L << attempt));
                }
            }
            log.warn("deliver batch {} exhausted in-memory retries -> persist", payload.batchId);
            queue.enqueue(payload);
        });
    }

    /** 单次 POST，成功(2xx)返回 true。 */
    boolean postOnce(BatchPayload payload) {
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getBackendUrl()))
                    .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-Session-Id", payload.sessionId == null ? "" : payload.sessionId)
                    .header("X-Batch-Id", payload.batchId == null ? "" : payload.batchId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 == 2) return true;
            log.warn("deliver batch {} got HTTP {}", payload.batchId, resp.statusCode());
            return false;
        } catch (Exception e) {
            log.warn("deliver batch {} failed: {}", payload.batchId, e.toString());
            return false;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

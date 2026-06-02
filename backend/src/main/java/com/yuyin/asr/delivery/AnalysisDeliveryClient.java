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
 * 把聚合批 POST 给分析后端（占位）。投递与转写解耦：独立线程池 + 退避重试。
 * 幂等键 X-Batch-Id，下游/重试都安全。
 */
@Component
public class AnalysisDeliveryClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDeliveryClient.class);

    private final AsrProperties.Delivery cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "asr-delivery");
        t.setDaemon(true);
        return t;
    });

    public AnalysisDeliveryClient(AsrProperties props, ObjectMapper mapper) {
        this.cfg = props.getDelivery();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
    }

    /** 异步投递；失败按指数退避重试，最终失败仅记录（后续可接本地队列落盘）。 */
    public void deliver(BatchPayload payload) {
        pool.submit(() -> {
            byte[] body;
            try {
                body = mapper.writeValueAsBytes(payload);
            } catch (Exception e) {
                log.error("serialize batch {} failed", payload.batchId, e);
                return;
            }
            for (int attempt = 0; attempt <= cfg.getMaxRetries(); attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getBackendUrl()))
                            .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
                            .header("Content-Type", "application/json")
                            .header("X-Session-Id", payload.sessionId)
                            .header("X-Batch-Id", payload.batchId)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
                    HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() / 100 == 2) {
                        log.debug("delivered batch {} (attempt {})", payload.batchId, attempt + 1);
                        return;
                    }
                    log.warn("deliver batch {} got HTTP {} (attempt {})", payload.batchId, resp.statusCode(), attempt + 1);
                } catch (Exception e) {
                    log.warn("deliver batch {} failed (attempt {}): {}", payload.batchId, attempt + 1, e.toString());
                }
                if (attempt < cfg.getMaxRetries()) {
                    sleep(cfg.getRetryBaseMs() * (1L << attempt));
                }
            }
            log.error("deliver batch {} exhausted retries, DROPPED", payload.batchId);
        });
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

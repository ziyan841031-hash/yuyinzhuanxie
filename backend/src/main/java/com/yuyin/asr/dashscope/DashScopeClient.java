package com.yuyin.asr.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyin.asr.common.Ids;
import com.yuyin.asr.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 连接阿里百炼 FUN-ASR 实时转写（一条连接对应一个识别任务）。
 * 协议：run-task(文本) -> task-started -> 二进制音频 + result-generated -> finish-task -> task-finished。
 */
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    /** 事件回调，由 AsrSession 实现。 */
    public interface Callbacks {
        void onStarted();
        /** sentenceEnd=false 为中间结果，true 为定稿句。 */
        void onSentence(String text, boolean sentenceEnd, long beginTime, long endTime);
        void onFinished();
        void onError(String code, String message);
    }

    private final AsrProperties.DashScope cfg;
    private final ObjectMapper mapper;
    private final Callbacks cb;
    private final String taskId = Ids.taskId();

    private volatile WebSocket ws;
    private volatile boolean started = false;
    private volatile boolean closed = false;

    public DashScopeClient(AsrProperties.DashScope cfg, ObjectMapper mapper, Callbacks cb) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.cb = cb;
    }

    public void connect() {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            cb.onError("NO_API_KEY", "DASHSCOPE_API_KEY 未配置");
            return;
        }
        HttpClient http = HttpClient.newHttpClient();
        http.newWebSocketBuilder()
                .header("Authorization", "bearer " + cfg.getApiKey())
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .buildAsync(URI.create(cfg.getEndpoint()), new Listener())
                .whenComplete((socket, err) -> {
                    if (err != null) {
                        log.error("connect DashScope failed", err);
                        cb.onError("CONNECT_FAILED", err.getMessage());
                        return;
                    }
                    this.ws = socket;
                    sendRunTask();
                });
    }

    private void sendRunTask() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", cfg.getFormat());
        params.put("sample_rate", cfg.getSampleRate());
        params.put("max_sentence_silence", cfg.getMaxSentenceSilence());
        params.put("punctuation_prediction_enabled", cfg.isPunctuationPredictionEnabled());
        params.put("heartbeat", cfg.isHeartbeat());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", cfg.getModel());
        payload.put("parameters", params);
        payload.put("input", new LinkedHashMap<>());

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("header", header);
        msg.put("payload", payload);
        sendText(msg);
    }

    /** 透传一帧音频（PCM 16bit）。task-started 之前会被忽略。 */
    public void sendAudio(ByteBuffer pcm) {
        WebSocket w = ws;
        if (w == null || !started || closed) return;
        w.sendBinary(pcm, true);
    }

    /** 通知收尾。 */
    public void finish() {
        WebSocket w = ws;
        if (w == null || closed) return;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", new LinkedHashMap<>());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("header", header);
        msg.put("payload", payload);
        sendText(msg);
    }

    public void close() {
        closed = true;
        WebSocket w = ws;
        if (w != null) {
            try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Exception ignored) {}
        }
    }

    private void sendText(Map<String, Object> msg) {
        WebSocket w = ws;
        if (w == null) return;
        try {
            w.sendText(mapper.writeValueAsString(msg), true);
        } catch (Exception e) {
            log.error("send text to DashScope failed", e);
        }
    }

    private void handleEvent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode header = root.path("header");
            String event = header.path("event").asText("");
            switch (event) {
                case "task-started" -> {
                    started = true;
                    log.debug("DashScope task-started {}", taskId);
                    cb.onStarted();
                }
                case "result-generated" -> {
                    JsonNode s = root.path("payload").path("output").path("sentence");
                    if (!s.isMissingNode()) {
                        cb.onSentence(
                                s.path("text").asText(""),
                                s.path("sentence_end").asBoolean(false),
                                s.path("begin_time").asLong(0),
                                s.path("end_time").asLong(0));
                    }
                }
                case "task-finished" -> {
                    log.debug("DashScope task-finished {}", taskId);
                    cb.onFinished();
                }
                case "task-failed" -> {
                    String code = header.path("error_code").asText("TASK_FAILED");
                    String message = header.path("error_message").asText("");
                    log.warn("DashScope task-failed {} {} {}", taskId, code, message);
                    cb.onError(code, message);
                }
                default -> { /* 忽略未知事件 */ }
            }
        } catch (Exception e) {
            log.error("parse DashScope event failed: {}", json, e);
        }
    }

    /** WebSocket 监听器：文本可能分片，累积到 last=true 再解析。 */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String json = buf.toString();
                buf.setLength(0);
                handleEvent(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) {
                log.warn("DashScope closed unexpectedly: {} {}", statusCode, reason);
                cb.onError("WS_CLOSED", "code=" + statusCode + " reason=" + reason);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("DashScope ws error", error);
            cb.onError("WS_ERROR", error.getMessage());
        }
    }
}

package com.yuyin.asr.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyin.asr.common.Ids;
import com.yuyin.asr.config.AsrProperties;
import com.yuyin.asr.delivery.AnalysisDeliveryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 前端入站端点 /ws/asr。
 * 文本帧：控制消息 {"type":"start"|"stop", "config":{...聚合触发器覆盖...}}。
 * 二进制帧：PCM 16bit 音频，透传 DashScope。
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final AsrProperties props;
    private final AnalysisDeliveryClient delivery;
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> conns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "asr-aggregator");
        t.setDaemon(true);
        return t;
    });

    public AsrWebSocketHandler(ObjectMapper mapper, AsrProperties props, AnalysisDeliveryClient delivery) {
        this.mapper = mapper;
        this.props = props;
        this.delivery = delivery;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 装饰为并发安全会话：聚合/重连/心跳多线程写同一连接时序列化，避免 TEXT_PARTIAL_WRITING
        conns.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024));
        log.info("client connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "start" -> {
                    String sid = root.path("sessionId").asText(Ids.sessionId());
                    AsrProperties.Aggregator override = parseOverride(root.path("config"));
                    WebSocketSession conn = conns.getOrDefault(session.getId(), session);
                    AsrSession s = new AsrSession(sid, conn, mapper, props, scheduler, delivery);
                    sessions.put(session.getId(), s);
                    s.start(override);
                }
                case "stop" -> {
                    AsrSession s = sessions.get(session.getId());
                    if (s != null) s.stop();
                }
                case "ping" -> sendPong(conns.getOrDefault(session.getId(), session));
                default -> log.debug("unknown control type: {}", type);
            }
        } catch (Exception e) {
            log.warn("handle text failed: {}", e.toString());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AsrSession s = sessions.get(session.getId());
        if (s != null) s.onAudio(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AsrSession s = sessions.remove(session.getId());
        if (s != null) s.dispose();
        conns.remove(session.getId());
        log.info("client disconnected: {} ({})", session.getId(), status);
    }

    private void sendPong(WebSocketSession session) {
        try {
            if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        } catch (Exception ignored) {
        }
    }

    /** 把前端 config 覆盖到默认聚合配置上（仅覆盖出现的字段）。 */
    private AsrProperties.Aggregator parseOverride(JsonNode cfg) {
        AsrProperties.Aggregator base = props.getAggregator().copy();
        if (cfg == null || cfg.isMissingNode() || !cfg.isObject()) return base;
        if (cfg.has("charThreshold")) base.setCharThreshold(cfg.get("charThreshold").asInt());
        if (cfg.has("sentenceThreshold")) base.setSentenceThreshold(cfg.get("sentenceThreshold").asInt());
        if (cfg.has("intervalMs")) base.setIntervalMs(cfg.get("intervalMs").asLong());
        if (cfg.has("silenceFlushMs")) base.setSilenceFlushMs(cfg.get("silenceFlushMs").asLong());
        if (cfg.has("maxBufferMs")) base.setMaxBufferMs(cfg.get("maxBufferMs").asLong());
        if (cfg.has("minCharsToFlush")) base.setMinCharsToFlush(cfg.get("minCharsToFlush").asInt());
        if (cfg.has("overlapSentences")) base.setOverlapSentences(cfg.get("overlapSentences").asInt());
        return base;
    }
}

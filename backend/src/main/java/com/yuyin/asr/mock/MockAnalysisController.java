package com.yuyin.asr.mock;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 占位分析后端。仅打印收到的批，返回 received:true。后续替换为真实接口。 */
@RestController
@RequestMapping("/mock")
public class MockAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(MockAnalysisController.class);

    @PostMapping("/analysis")
    public ResponseEntity<Map<String, Object>> analysis(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Batch-Id", required = false) String batchId,
            @RequestBody JsonNode body) {
        log.info("[MOCK] received batch session={} batch={} seq={} by={} chars={} final={} text=\"{}\"",
                sessionId, batchId,
                body.path("batchSeq").asLong(),
                body.path("triggeredBy").asText(),
                body.path("chars").asInt(),
                body.path("isFinal").asBoolean(),
                body.path("text").asText());
        return ResponseEntity.ok(Map.of("received", true, "batchId", batchId == null ? "" : batchId));
    }
}

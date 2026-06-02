package com.yuyin.asr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 绑定 application.yml 中 asr.* 配置。
 * 聚合触发器在 {@link Aggregator} 内，前端可在 start 消息里按会话覆盖（见 AggregatorConfig）。
 */
@ConfigurationProperties(prefix = "asr")
public class AsrProperties {

    @NestedConfigurationProperty
    private DashScope dashscope = new DashScope();
    @NestedConfigurationProperty
    private Aggregator aggregator = new Aggregator();
    @NestedConfigurationProperty
    private Delivery delivery = new Delivery();

    public DashScope getDashscope() { return dashscope; }
    public void setDashscope(DashScope dashscope) { this.dashscope = dashscope; }
    public Aggregator getAggregator() { return aggregator; }
    public void setAggregator(Aggregator aggregator) { this.aggregator = aggregator; }
    public Delivery getDelivery() { return delivery; }
    public void setDelivery(Delivery delivery) { this.delivery = delivery; }

    public static class DashScope {
        private String endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";
        private String apiKey = "";
        private String model = "fun-asr-realtime";
        private int sampleRate = 16000;
        private String format = "pcm";
        private int maxSentenceSilence = 800;
        private boolean punctuationPredictionEnabled = true;
        private boolean heartbeat = true;
        private long connectTimeoutMs = 8000;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getMaxSentenceSilence() { return maxSentenceSilence; }
        public void setMaxSentenceSilence(int v) { this.maxSentenceSilence = v; }
        public boolean isPunctuationPredictionEnabled() { return punctuationPredictionEnabled; }
        public void setPunctuationPredictionEnabled(boolean v) { this.punctuationPredictionEnabled = v; }
        public boolean isHeartbeat() { return heartbeat; }
        public void setHeartbeat(boolean heartbeat) { this.heartbeat = heartbeat; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long v) { this.connectTimeoutMs = v; }
    }

    /** 聚合触发器配置；同时作为前端按会话覆盖的载体。 */
    public static class Aggregator {
        private int charThreshold = 200;
        private int sentenceThreshold = 0;
        private long intervalMs = 8000;
        private long silenceFlushMs = 1500;
        private long maxBufferMs = 15000;
        private int minCharsToFlush = 10;
        private int overlapSentences = 1;

        public Aggregator copy() {
            Aggregator c = new Aggregator();
            c.charThreshold = charThreshold;
            c.sentenceThreshold = sentenceThreshold;
            c.intervalMs = intervalMs;
            c.silenceFlushMs = silenceFlushMs;
            c.maxBufferMs = maxBufferMs;
            c.minCharsToFlush = minCharsToFlush;
            c.overlapSentences = overlapSentences;
            return c;
        }

        public int getCharThreshold() { return charThreshold; }
        public void setCharThreshold(int v) { this.charThreshold = v; }
        public int getSentenceThreshold() { return sentenceThreshold; }
        public void setSentenceThreshold(int v) { this.sentenceThreshold = v; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long v) { this.intervalMs = v; }
        public long getSilenceFlushMs() { return silenceFlushMs; }
        public void setSilenceFlushMs(long v) { this.silenceFlushMs = v; }
        public long getMaxBufferMs() { return maxBufferMs; }
        public void setMaxBufferMs(long v) { this.maxBufferMs = v; }
        public int getMinCharsToFlush() { return minCharsToFlush; }
        public void setMinCharsToFlush(int v) { this.minCharsToFlush = v; }
        public int getOverlapSentences() { return overlapSentences; }
        public void setOverlapSentences(int v) { this.overlapSentences = v; }
    }

    public static class Delivery {
        private String backendUrl = "http://localhost:8080/mock/analysis";
        private int maxRetries = 5;
        private long retryBaseMs = 1000;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;

        public String getBackendUrl() { return backendUrl; }
        public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
        public long getRetryBaseMs() { return retryBaseMs; }
        public void setRetryBaseMs(long v) { this.retryBaseMs = v; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
    }
}

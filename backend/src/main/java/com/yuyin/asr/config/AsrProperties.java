package com.yuyin.asr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

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
        // ── M2/M3 识别调优（null/空 表示不下发该参数，用模型默认）──
        private String vocabularyId = "";                       // 热词表 ID，提升专有名词/术语识别
        private Boolean semanticPunctuationEnabled = null;       // 语义断句(更自然的句界/标点)
        private Boolean disfluencyRemovalEnabled = null;         // 顺滑：去除“嗯/啊”等口水词
        private Boolean inverseTextNormalizationEnabled = null;  // ITN：数字/日期/单位规整
        private List<String> languageHints = new ArrayList<>();  // 语种提示，如 [zh, en]
        // ── M4 稳定性 ──
        private long sessionMaxSeconds = 600;   // 接近单任务时长上限前主动滚动重开，<=0 关闭
        private long reconnectBaseMs = 1000;     // 断线重连退避基数
        private long reconnectMaxMs = 16000;     // 退避上限
        private int maxReconnectAttempts = 8;    // 连续重连失败上限，超过则上报致命错误
        private int audioBufferFrames = 200;     // 重连/切换间隙音频缓冲帧上限(约20s@100ms)

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
        public long getSessionMaxSeconds() { return sessionMaxSeconds; }
        public void setSessionMaxSeconds(long v) { this.sessionMaxSeconds = v; }
        public long getReconnectBaseMs() { return reconnectBaseMs; }
        public void setReconnectBaseMs(long v) { this.reconnectBaseMs = v; }
        public long getReconnectMaxMs() { return reconnectMaxMs; }
        public void setReconnectMaxMs(long v) { this.reconnectMaxMs = v; }
        public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
        public void setMaxReconnectAttempts(int v) { this.maxReconnectAttempts = v; }
        public int getAudioBufferFrames() { return audioBufferFrames; }
        public void setAudioBufferFrames(int v) { this.audioBufferFrames = v; }
        public String getVocabularyId() { return vocabularyId; }
        public void setVocabularyId(String v) { this.vocabularyId = v; }
        public Boolean getSemanticPunctuationEnabled() { return semanticPunctuationEnabled; }
        public void setSemanticPunctuationEnabled(Boolean v) { this.semanticPunctuationEnabled = v; }
        public Boolean getDisfluencyRemovalEnabled() { return disfluencyRemovalEnabled; }
        public void setDisfluencyRemovalEnabled(Boolean v) { this.disfluencyRemovalEnabled = v; }
        public Boolean getInverseTextNormalizationEnabled() { return inverseTextNormalizationEnabled; }
        public void setInverseTextNormalizationEnabled(Boolean v) { this.inverseTextNormalizationEnabled = v; }
        public List<String> getLanguageHints() { return languageHints; }
        public void setLanguageHints(List<String> v) { this.languageHints = v; }

        /** 浅拷贝，供按会话覆盖识别参数用。 */
        public DashScope copy() {
            DashScope c = new DashScope();
            c.endpoint = endpoint;
            c.apiKey = apiKey;
            c.model = model;
            c.sampleRate = sampleRate;
            c.format = format;
            c.maxSentenceSilence = maxSentenceSilence;
            c.punctuationPredictionEnabled = punctuationPredictionEnabled;
            c.heartbeat = heartbeat;
            c.connectTimeoutMs = connectTimeoutMs;
            c.sessionMaxSeconds = sessionMaxSeconds;
            c.reconnectBaseMs = reconnectBaseMs;
            c.reconnectMaxMs = reconnectMaxMs;
            c.maxReconnectAttempts = maxReconnectAttempts;
            c.audioBufferFrames = audioBufferFrames;
            c.vocabularyId = vocabularyId;
            c.semanticPunctuationEnabled = semanticPunctuationEnabled;
            c.disfluencyRemovalEnabled = disfluencyRemovalEnabled;
            c.inverseTextNormalizationEnabled = inverseTextNormalizationEnabled;
            c.languageHints = (languageHints == null) ? new ArrayList<>() : new ArrayList<>(languageHints);
            return c;
        }
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
        private String queueDir = "./data/delivery-queue";   // 投递失败落盘目录(防丢)
        private long queueFlushMs = 10000;                    // 磁盘队列重试扫描间隔

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
        public String getQueueDir() { return queueDir; }
        public void setQueueDir(String v) { this.queueDir = v; }
        public long getQueueFlushMs() { return queueFlushMs; }
        public void setQueueFlushMs(long v) { this.queueFlushMs = v; }
    }
}

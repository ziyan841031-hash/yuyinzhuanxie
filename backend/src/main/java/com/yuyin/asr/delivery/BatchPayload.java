package com.yuyin.asr.delivery;

import com.yuyin.asr.aggregate.Sentence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 投递给分析后端的一批数据（契约见 docs/设计方案.md 第7节）。 */
public class BatchPayload {
    public String sessionId;
    public String batchId;
    public long batchSeq;
    public boolean isFinal;
    public String createdAt = Instant.now().toString();
    public String triggeredBy;
    public int chars;
    public List<Item> context = new ArrayList<>();
    public List<Item> sentences = new ArrayList<>();
    public String text;

    public static class Item {
        public long sentenceId;
        public long beginTime;
        public long endTime;
        public String text;

        public Item() {}
        public Item(Sentence s) {
            this.sentenceId = s.getSentenceId();
            this.beginTime = s.getBeginTime();
            this.endTime = s.getEndTime();
            this.text = s.getText();
        }
    }
}

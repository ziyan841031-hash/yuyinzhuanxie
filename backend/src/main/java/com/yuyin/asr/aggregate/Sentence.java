package com.yuyin.asr.aggregate;

/** 一条定稿句（sentence_end=true）。 */
public class Sentence {
    private final long sentenceId;
    private final String text;
    private final long beginTime;
    private final long endTime;

    public Sentence(long sentenceId, String text, long beginTime, long endTime) {
        this.sentenceId = sentenceId;
        this.text = text == null ? "" : text;
        this.beginTime = beginTime;
        this.endTime = endTime;
    }

    public long getSentenceId() { return sentenceId; }
    public String getText() { return text; }
    public long getBeginTime() { return beginTime; }
    public long getEndTime() { return endTime; }
}

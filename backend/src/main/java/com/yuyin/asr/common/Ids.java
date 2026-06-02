package com.yuyin.asr.common;

import java.util.UUID;

public final class Ids {
    private Ids() {}

    /** DashScope task_id：32 位十六进制。 */
    public static String taskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String sessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

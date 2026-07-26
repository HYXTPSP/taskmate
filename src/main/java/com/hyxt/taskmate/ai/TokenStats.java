package com.hyxt.taskmate.ai;

import com.hyxt.taskmate.TaskmateClient;

/** token 消耗统计:本次任务 / 本次游戏会话。 */
public final class TokenStats {

    private static long sessionTotal = 0;
    private static long taskTotal = 0;

    private TokenStats() {}

    public static synchronized void record(long promptTokens, long completionTokens) {
        long t = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        sessionTotal += t;
        taskTotal += t;
    }

    /** 新任务(玩家新指令)开始时调用 */
    public static synchronized void resetTask() {
        taskTotal = 0;
    }

    public static synchronized long taskTotal() {
        return taskTotal;
    }

    public static synchronized long sessionTotal() {
        return sessionTotal;
    }

    /** 是否超过单任务上限(0 = 不限) */
    public static synchronized boolean taskCapExceeded() {
        long cap = TaskmateClient.CONFIG.maxTokensPerTask;
        return cap > 0 && taskTotal >= cap;
    }
}

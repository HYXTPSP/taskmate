package com.hyxt.taskmate.api;

import com.hyxt.taskmate.plan.TaskStep;
import net.minecraft.client.MinecraftClient;

import java.util.Map;

/** 框架提供给 StepHandler 的上下文与控制接口。 */
public interface StepControl {

    MinecraftClient client();

    /** 当前步骤(含 AI 给的参数,见 TaskStep.getInt/getString/getStringList) */
    TaskStep step();

    /** 本步骤已经执行的 tick 数(含宽限期) */
    int ticks();

    /** 本步骤开始以来背包的净增量(物品注册名 -> 数量,只含正增量) */
    Map<String, Integer> gainsSoFar();

    /** 标记为持续性步骤:不受超时限制,提示玩家可手动停止 */
    void markOpenEnded(String playerHint);

    void complete();

    void fail(String reason);
}

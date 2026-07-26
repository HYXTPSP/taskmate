package com.hyxt.taskmate.exec;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import com.hyxt.taskmate.TaskmateClient;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 所有对 Baritone 的调用都收拢在这个类里。
 * 注意:必须先用 BaritoneCheck.available() 确认 API 类存在后才能触碰本类,
 * 否则(未装 Baritone 或装了 standalone 混淆版)类加载会直接抛错。
 */
public final class BaritoneBridge {

    private BaritoneBridge() {}

    private static IBaritone b() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** 把安全边界配置透传给 Baritone */
    public static void applySettings() {
        try {
            Settings s = BaritoneAPI.getSettings();
            s.allowBreak.value = TaskmateClient.CONFIG.allowBreak;
            s.allowPlace.value = TaskmateClient.CONFIG.allowPlace;
            s.allowSprint.value = true;
        } catch (Throwable t) {
            System.err.println("[Taskmate] 应用 Baritone 设置失败: " + t);
        }
    }

    public static void goTo(int x, int y, int z) {
        b().getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
    }

    /** 走到某位置附近 range 格内 */
    public static void goNear(net.minecraft.util.math.BlockPos pos, int range) {
        b().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(pos, range));
    }

    public static void goToBlock(Block block) {
        b().getGetToBlockProcess().getToBlock(block);
    }

    public static void mine(int count, String... blockNames) {
        if (count > 0) {
            b().getMineProcess().mineByName(count, blockNames);
        } else {
            b().getMineProcess().mineByName(blockNames);
        }
    }

    public static void follow(String playerName) {
        b().getFollowProcess().follow(e -> isPlayerNamed(e, playerName));
    }

    private static boolean isPlayerNamed(Entity e, String name) {
        return e instanceof PlayerEntity && e.getName().getString().equalsIgnoreCase(name);
    }

    public static void cancelAll() {
        try {
            b().getPathingBehavior().cancelEverything();
        } catch (Throwable t) {
            System.err.println("[Taskmate] 取消 Baritone 任务失败: " + t);
        }
    }

    /** 释放 Baritone 强制按下的所有按键(修复任务结束后左键/前进键卡住) */
    public static void releaseKeys() {
        try {
            b().getInputOverrideHandler().clearAllKeys();
        } catch (Throwable t) {
            System.err.println("[Taskmate] 释放 Baritone 按键失败: " + t);
        }
    }

    public static boolean isCustomGoalActive() {
        return b().getCustomGoalProcess().isActive();
    }

    public static boolean isGetToBlockActive() {
        return b().getGetToBlockProcess().isActive();
    }

    public static boolean isMineActive() {
        return b().getMineProcess().isActive();
    }

    public static boolean isPathing() {
        return b().getPathingBehavior().isPathing();
    }
}

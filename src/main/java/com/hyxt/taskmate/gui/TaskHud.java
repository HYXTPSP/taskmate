package com.hyxt.taskmate.gui;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.ai.AiSession;
import com.hyxt.taskmate.ai.TokenStats;
import com.hyxt.taskmate.exec.TaskExecutor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** 左上角任务状态条:空闲且不思考时不显示。 */
public final class TaskHud {

    private TaskHud() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.of(TaskmateClient.MOD_ID, "status"),
                (context, tickCounter) -> render(context));
    }

    private static void render(DrawContext context) {
        if (!TaskmateClient.CONFIG.showHud) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.inGameHud.getDebugHud().shouldShowDebugHud()) return;

        TaskExecutor.State state = TaskExecutor.INSTANCE.state();
        boolean thinking = AiSession.INSTANCE.isBusy();
        if (state == TaskExecutor.State.IDLE && !thinking) return;

        List<String> lines = new ArrayList<>();
        if (thinking) {
            lines.add("§bAI 思考中…");
        }
        switch (state) {
            case AWAITING_CONFIRM -> lines.add("§e等待确认计划(点聊天栏按钮或输入 "
                    + TaskmateClient.CONFIG.triggerPrefix + "执行)");
            case PAUSED -> lines.add("§6任务已暂停 —— " + TaskmateClient.CONFIG.triggerPrefix + "继续 恢复");
            case RUNNING -> {
                lines.add("§a任务执行中" + (TaskExecutor.INSTANCE.isAutoMode() ? " §6[自动模式]" : ""));
                String step = TaskExecutor.INSTANCE.hudStepLine();
                if (!step.isEmpty()) lines.add("§f" + step);
            }
            default -> {}
        }
        if (state != TaskExecutor.State.IDLE || thinking) {
            lines.add("§7tokens: " + TokenStats.taskTotal() + " (本任务) / " + TokenStats.sessionTotal() + " (会话)");
        }
        if (lines.isEmpty()) return;

        int x = 4, y = 4;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, client.textRenderer.getWidth(line));
        }
        int height = lines.size() * 11;
        context.fill(x - 2, y - 2, x + width + 2, y + height + 1, 0x90000000);
        for (String line : lines) {
            context.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFFFFFFF);
            y += 11;
        }
    }
}

package com.hyxt.taskmate.util;

import com.hyxt.taskmate.plan.Plan;
import com.hyxt.taskmate.plan.TaskStep;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** 聊天栏 UI:前缀消息、计划展示、可点击按钮。 */
public final class ChatUi {

    private ChatUi() {}

    private static final Text PREFIX = Text.literal("[AI] ").formatted(Formatting.AQUA, Formatting.BOLD);

    private static void add(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> client.inGameHud.getChatHud().addMessage(text));
    }

    public static void info(String msg) {
        add(Text.empty().append(PREFIX).append(Text.literal(msg).formatted(Formatting.GRAY)));
    }

    public static void success(String msg) {
        add(Text.empty().append(PREFIX).append(Text.literal(msg).formatted(Formatting.GREEN)));
    }

    public static void error(String msg) {
        add(Text.empty().append(PREFIX).append(Text.literal(msg).formatted(Formatting.RED)));
    }

    /** AI 的对话内容 */
    public static void ai(String msg) {
        add(Text.empty().append(PREFIX).append(Text.literal(msg).formatted(Formatting.WHITE)));
    }

    public static void thinking() {
        info("思考中…");
    }

    private static MutableText button(String label, String command, String hover, Formatting color) {
        return Text.literal(label).styled(style -> style
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    /** 展示计划 + 执行/取消按钮 */
    public static void showPlan(Plan plan, boolean needConfirm) {
        MutableText text = Text.empty()
                .append(PREFIX)
                .append(Text.literal("已拟定计划:").formatted(Formatting.YELLOW));
        int i = 1;
        for (TaskStep step : plan.steps) {
            text.append(Text.literal("\n  " + i + ". " + step.describe()).formatted(Formatting.WHITE));
            i++;
        }
        if (needConfirm) {
            text.append(Text.literal("\n  "))
                    .append(button("【✔ 执行】", "taskmate go", "只执行这一个计划,后续仍会询问", Formatting.GREEN))
                    .append(Text.literal("  "))
                    .append(button("【⚡ 自动】", "taskmate auto",
                            "执行并开启自动模式:本任务后续计划(含失败重规划)不再询问,直到任务完成或 #停止", Formatting.GOLD))
                    .append(Text.literal("  "))
                    .append(button("【✘ 取消】", "taskmate cancel", "放弃该计划", Formatting.RED));
        }
        add(text);
    }

    /** 任务暂停时的提示(带继续/终止按钮) */
    public static void pausedBanner(String reason) {
        add(Text.empty()
                .append(PREFIX)
                .append(Text.literal("任务已暂停(" + reason + ") ").formatted(Formatting.YELLOW))
                .append(button("【▶ 继续】", "taskmate resume", "从当前步骤继续执行", Formatting.GREEN))
                .append(Text.literal("  "))
                .append(button("【⏹ 终止】", "taskmate stop", "彻底终止任务", Formatting.RED)));
    }

    /** 任务开始执行时的提示(带终止按钮) */
    public static void runningBanner(String taskDesc) {
        add(Text.empty()
                .append(PREFIX)
                .append(Text.literal("开始执行: " + taskDesc + " ").formatted(Formatting.GREEN))
                .append(button("【⏹ 终止】", "taskmate stop", "立即终止任务", Formatting.RED)));
    }

    public static void stepBanner(int index, int total, String desc) {
        info("步骤 " + index + "/" + total + ": " + desc);
    }

    public static void help() {
        String p = com.hyxt.taskmate.TaskmateClient.CONFIG.triggerPrefix;
        add(Text.empty().append(PREFIX).append(Text.literal(
                "用法:聊天栏输入 " + p + " 开头的内容与 AI 交互\n" +
                "  " + p + "帮我砍20个橡木        —— 下达任务,AI 会拟定计划等你确认\n" +
                "  " + p + "先别挖了,去打死那只僵尸 —— 任务中途纠正\n" +
                "  " + p + "执行 / " + p + "取消          —— 确认或放弃当前计划\n" +
                "  " + p + "自动                  —— 执行并开启自动模式(本任务不再逐次询问)\n" +
                "  " + p + "继续                  —— 从暂停处继续(手动操作会自动暂停任务)\n" +
                "  " + p + "停止                  —— 立即终止正在执行的任务(也可用快捷键)\n" +
                "  " + p + "重置                  —— 清空 AI 对话上下文\n" +
                "  " + p + "设置                  —— 打开 API 配置界面\n" +
                "  " + p + "帮助                  —— 显示本帮助\n" +
                "普通聊天(不带 " + p + ")不受影响。").formatted(Formatting.GRAY)));
    }
}

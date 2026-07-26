package com.hyxt.taskmate.ai;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.config.ModConfig;
import com.hyxt.taskmate.exec.TaskExecutor;
import com.hyxt.taskmate.plan.PlanParser;
import com.hyxt.taskmate.util.ChatUi;
import net.minecraft.client.MinecraftClient;

/** AI 会话调度:发请求、收回复、分发到聊天/执行器。 */
public final class AiSession {

    public static final AiSession INSTANCE = new AiSession();

    private final Conversation conversation = new Conversation();
    private volatile boolean busy = false;
    private int parseRetries = 0;

    private AiSession() {}

    public Conversation conversation() {
        return conversation;
    }

    public boolean isBusy() {
        return busy;
    }

    public void reset() {
        conversation.clear();
        TaskExecutor.INSTANCE.hardReset();
        ContextStore.saveNow();
    }

    /** 玩家输入的触发前缀消息 */
    public void userMessage(String text) {
        ModConfig cfg = TaskmateClient.CONFIG;
        if (cfg.apiKey == null || cfg.apiKey.isBlank()) {
            ChatUi.error("尚未配置 API Key,输入 " + cfg.triggerPrefix + "设置 打开配置界面。");
            return;
        }
        if (busy) {
            ChatUi.error("上一条消息还在处理中,请稍候…");
            return;
        }
        TokenStats.resetTask(); // 新指令 = 新任务,重新计费
        String content;
        if (TaskExecutor.INSTANCE.isRunningOrPaused()) {
            TaskExecutor.INSTANCE.pauseForCorrection();
            content = "[任务事件] 玩家在任务执行中发来新指令,当前任务已暂停。\n"
                    + ContextBuilder.snapshot() + "\n玩家指令: " + text;
        } else {
            content = ContextBuilder.snapshot() + "\n玩家指令: " + text;
        }
        send(content, true);
    }

    /** 执行器上报的事件(步骤失败、任务完成等),会发起一次 AI 请求 */
    public void systemEvent(String event) {
        ModConfig cfg = TaskmateClient.CONFIG;
        if (cfg.apiKey == null || cfg.apiKey.isBlank() || busy) return;
        send("[任务事件] " + event + "\n" + ContextBuilder.snapshot(), false);
    }

    /** 只记入上下文、不发请求的事件(省 token) */
    public void noteEvent(String event) {
        conversation.addUser("[任务事件] " + event);
        ContextStore.saveNow();
    }

    private void send(String content, boolean echoThinking) {
        ModConfig cfg = TaskmateClient.CONFIG;
        conversation.addUser(content);
        busy = true;
        if (echoThinking) ChatUi.thinking();
        AiClient.chat(Prompts.system(cfg), conversation.messages(cfg.maxHistoryMessages), cfg)
                .whenComplete((result, err) -> MinecraftClient.getInstance().execute(() -> {
                    busy = false;
                    if (err != null) {
                        conversation.dropLastIfUser();
                        ChatUi.error("请求失败: " + rootMessage(err));
                        return;
                    }
                    TokenStats.record(result.promptTokens(), result.completionTokens());
                    handleReply(result.content());
                }));
    }

    private void handleReply(String raw) {
        conversation.addAssistant(raw);
        ContextStore.saveNow();
        PlanParser.Result result;
        try {
            result = PlanParser.parse(raw);
            parseRetries = 0;
        } catch (Exception e) {
            if (parseRetries++ < 1) {
                send("[格式错误] 你上一条回复无法按约定解析(" + e.getMessage()
                        + ")。请重新回复,只输出一个合法的 JSON 对象,严格遵守系统提示中的格式。", false);
            } else {
                parseRetries = 0;
                ChatUi.error("AI 返回的格式无法解析: " + e.getMessage());
            }
            return;
        }
        if (result.message != null && !result.message.isBlank()) {
            ChatUi.ai(result.message);
        }
        if (result.type == PlanParser.ReplyType.PLAN && result.plan != null) {
            TaskExecutor.INSTANCE.onPlanReceived(result.plan);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}

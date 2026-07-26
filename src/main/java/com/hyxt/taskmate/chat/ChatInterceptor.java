package com.hyxt.taskmate.chat;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.ai.AiSession;
import com.hyxt.taskmate.exec.TaskExecutor;
import com.hyxt.taskmate.gui.ConfigScreen;
import com.hyxt.taskmate.util.ChatUi;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/** 拦截以配置前缀(默认 #)开头的聊天消息:不发送到服务器,转交 AI 处理。 */
public final class ChatInterceptor {

    private ChatInterceptor() {}

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String prefix = TaskmateClient.CONFIG.triggerPrefix;
            if (prefix == null || prefix.isEmpty()) prefix = "#";
            if (message != null && message.startsWith(prefix)) {
                String content = message.substring(prefix.length()).trim();
                MinecraftClient.getInstance().execute(() -> handle(content));
                return false; // 拦截,不发到服务器
            }
            return true;
        });
    }

    private static void handle(String msg) {
        if (msg.isBlank()) {
            ChatUi.help();
            return;
        }
        switch (msg.toLowerCase(Locale.ROOT)) {
            case "stop", "停止", "终止" -> TaskExecutor.INSTANCE.stopAll("玩家指令");
            case "go", "执行", "确认" -> TaskExecutor.INSTANCE.confirmPending();
            case "auto", "自动", "自动执行" -> TaskExecutor.INSTANCE.confirmAuto();
            case "cancel", "取消" -> TaskExecutor.INSTANCE.cancelPending();
            case "resume", "继续" -> TaskExecutor.INSTANCE.resume();
            case "reset", "重置" -> {
                AiSession.INSTANCE.reset();
                ChatUi.info("对话上下文已清空。");
            }
            case "config", "设置", "配置" -> TaskmateClient.openScreenSoon(new ConfigScreen(null));
            case "help", "帮助" -> ChatUi.help();
            default -> AiSession.INSTANCE.userMessage(msg);
        }
    }
}

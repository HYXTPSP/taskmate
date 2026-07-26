package com.hyxt.taskmate.chat;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.ai.AiSession;
import com.hyxt.taskmate.exec.TaskExecutor;
import com.hyxt.taskmate.gui.ConfigScreen;
import com.hyxt.taskmate.util.ChatUi;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /taskmate 客户端指令,主要给聊天栏可点击按钮使用。 */
public final class ClientCommands {

    private ClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("taskmate")
                        .then(literal("go").executes(ctx -> {
                            TaskExecutor.INSTANCE.confirmPending();
                            return 1;
                        }))
                        .then(literal("auto").executes(ctx -> {
                            TaskExecutor.INSTANCE.confirmAuto();
                            return 1;
                        }))
                        .then(literal("cancel").executes(ctx -> {
                            TaskExecutor.INSTANCE.cancelPending();
                            return 1;
                        }))
                        .then(literal("stop").executes(ctx -> {
                            TaskExecutor.INSTANCE.stopAll("玩家点击终止");
                            return 1;
                        }))
                        .then(literal("resume").executes(ctx -> {
                            TaskExecutor.INSTANCE.resume();
                            return 1;
                        }))
                        .then(literal("config").executes(ctx -> {
                            TaskmateClient.openScreenSoon(new ConfigScreen(null));
                            return 1;
                        }))
                        .then(literal("reset").executes(ctx -> {
                            AiSession.INSTANCE.reset();
                            ChatUi.info("对话上下文已清空。");
                            return 1;
                        }))
                        .executes(ctx -> {
                            ChatUi.help();
                            return 1;
                        })
        ));
    }
}

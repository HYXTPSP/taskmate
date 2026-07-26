package com.hyxt.taskmate;

import com.hyxt.taskmate.ai.ContextStore;
import com.hyxt.taskmate.chat.ChatInterceptor;
import com.hyxt.taskmate.chat.ClientCommands;
import com.hyxt.taskmate.config.ModConfig;
import com.hyxt.taskmate.exec.ActionRegistry;
import com.hyxt.taskmate.exec.TaskExecutor;
import com.hyxt.taskmate.gui.TaskHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class TaskmateClient implements ClientModInitializer {

    public static final String MOD_ID = "taskmate";
    public static ModConfig CONFIG;
    public static KeyBinding STOP_KEY;

    private static Screen scheduledScreen = null;

    @Override
    public void onInitializeClient() {
        CONFIG = ModConfig.load();
        ActionRegistry.init(); // 核心动作 + 插件动作

        STOP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmate.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.taskmate"));

        ChatInterceptor.register();
        ClientCommands.register();
        TaskHud.register();

        // 进出世界:切换/保存 对话上下文与地点记忆
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ContextStore.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ContextStore.onDisconnect());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (STOP_KEY.wasPressed()) {
                TaskExecutor.INSTANCE.stopAll("快捷键");
            }
            if (scheduledScreen != null && client.currentScreen == null) {
                Screen s = scheduledScreen;
                scheduledScreen = null;
                client.setScreen(s);
            }
            com.hyxt.taskmate.exec.SurvivalManager.tick(client);
            TaskExecutor.INSTANCE.tick(client);
        });
    }

    /** 从指令/聊天点击里打开界面需要延迟到下一 tick(聊天界面关闭后) */
    public static void openScreenSoon(Screen screen) {
        scheduledScreen = screen;
    }
}

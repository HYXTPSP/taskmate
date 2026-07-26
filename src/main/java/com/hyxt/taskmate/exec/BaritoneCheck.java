package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.util.ChatUi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Baritone 可用性检测。
 * 这个类里绝不能 import 任何 baritone 类 —— 只有确认 API 类真实存在后,
 * 才允许去碰 BaritoneBridge(否则装了 standalone 版会在类加载时直接崩溃:
 * standalone 发行版连 baritone.api 都被混淆了,必须用 baritone-api-fabric 发行版)。
 */
public final class BaritoneCheck {

    private static Boolean available = null;
    private static boolean warned = false;

    private BaritoneCheck() {}

    public static boolean available() {
        if (available == null) {
            boolean modLoaded = FabricLoader.getInstance().isModLoaded("baritone");
            boolean apiPresent = false;
            if (modLoaded) {
                try {
                    Class.forName("baritone.api.BaritoneAPI", false, BaritoneCheck.class.getClassLoader());
                    apiPresent = true;
                } catch (Throwable ignored) {
                }
            }
            available = modLoaded && apiPresent;
            if (modLoaded && !apiPresent && !warned) {
                warned = true;
                ChatUi.error("检测到你安装的可能是 standalone 版 Baritone(API 类被混淆,无法对接)。"
                        + "请从 Baritone Releases 下载 baritone-api-fabric-1.15.0.jar 替换 mods 里的 standalone 版,"
                        + "它同样是完整可用的 Baritone。");
            }
        }
        return available;
    }
}

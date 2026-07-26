package com.hyxt.taskmate.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 背包/槽位工具。
 * 玩家背包索引:0-8 快捷栏,9-35 主背包。
 * PlayerScreenHandler 槽位:0 输出,1-4 合成格,5-8 盔甲,9-35 主背包,36-44 快捷栏,45 副手。
 * 打开容器时(N 个容器槽):容器 0..N-1,主背包 N..N+26,快捷栏 N+27..N+35。
 */
public final class InventoryHelper {

    private InventoryHelper() {}

    public static String normalizeId(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    public static String idOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath();
    }

    public static boolean matches(ItemStack stack, String itemName) {
        return !stack.isEmpty() && idOf(stack).equals(normalizeId(itemName));
    }

    /** 全背包(含盔甲/副手)中某物品总数 */
    public static int count(ClientPlayerEntity player, String itemName) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (matches(s, itemName)) total += s.getCount();
        }
        return total;
    }

    /** 在主背包+快捷栏(0..35)中找第一个匹配的槽位,快捷栏优先;找不到返回 -1 */
    public static int findSlot(ClientPlayerEntity player, Predicate<ItemStack> pred) {
        for (int i = 0; i < 9; i++) {
            if (pred.test(player.getInventory().getStack(i))) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (pred.test(player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    public static int findSlot(ClientPlayerEntity player, String itemName) {
        return findSlot(player, s -> matches(s, itemName));
    }

    /** 背包索引 -> PlayerScreenHandler 槽位 id(仅在未打开容器时用) */
    public static int playerHandlerSlot(int invSlot) {
        return invSlot < 9 ? 36 + invSlot : invSlot;
    }

    /** 背包索引 -> 打开了 N 容器槽的 handler 的槽位 id */
    public static int containerHandlerSlot(int containerSlots, int invSlot) {
        return invSlot < 9 ? containerSlots + 27 + invSlot : containerSlots + (invSlot - 9);
    }

    /**
     * 确保某背包槽位的物品到快捷栏并选中,返回 false 表示失败。
     * 仅在没有打开容器界面时调用。
     */
    public static boolean selectInHotbar(MinecraftClient client, int invSlot) {
        ClientPlayerEntity player = client.player;
        if (player == null || invSlot < 0) return false;
        if (invSlot < 9) {
            player.getInventory().setSelectedSlot(invSlot);
            return true;
        }
        int hotbar = player.getInventory().getSelectedSlot();
        // SWAP:把 invSlot 与当前选中的快捷栏槽交换
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId,
                playerHandlerSlot(invSlot), hotbar, SlotActionType.SWAP, player);
        return true;
    }

    /** 对当前打开的 handler 做一次 shift 快速移动 */
    public static void quickMove(MinecraftClient client, ScreenHandler handler, int slotId) {
        client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
    }
}

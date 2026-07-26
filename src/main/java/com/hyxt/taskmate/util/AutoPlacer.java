package com.hyxt.taskmate.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 可复用的"把背包里的方块放出来"小状态机。
 * 每 tick 调用 tick(),直到返回 PLACED 或 FAILED。
 */
public final class AutoPlacer {

    public enum State { WORKING, PLACED, FAILED }

    public record Result(State state, BlockPos pos, String reason) {}

    private final String blockName;
    private BlockPos expected;
    private int attempts;
    private int cooldown;

    public AutoPlacer(String blockName) {
        this.blockName = InventoryHelper.normalizeId(blockName);
    }

    public Result tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return new Result(State.FAILED, null, "玩家不在世界中");
        }
        // 校验上一次尝试是否成功
        if (expected != null) {
            if (client.world.getBlockState(expected).getBlock()
                    == Registries.BLOCK.get(Identifier.of("minecraft", blockName))) {
                return new Result(State.PLACED, expected, null);
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return new Result(State.WORKING, null, null);
        }
        if (attempts++ > 12) {
            return new Result(State.FAILED, null, "找不到合适的位置放置 " + blockName);
        }
        // 手上没拿着目标方块 → 先切换
        if (!InventoryHelper.matches(client.player.getInventory().getSelectedStack(), blockName)) {
            int slot = InventoryHelper.findSlot(client.player, blockName);
            if (slot < 0) {
                return new Result(State.FAILED, null, "背包里没有 " + blockName);
            }
            InventoryHelper.selectInHotbar(client, slot);
            cooldown = 2;
            return new Result(State.WORKING, null, null);
        }
        BlockPlacer.Spot spot = BlockPlacer.findSpot(client);
        if (spot == null) {
            cooldown = 5;
            return new Result(State.WORKING, null, null); // 继续找,attempts 会兜底
        }
        expected = BlockPlacer.place(client, spot);
        cooldown = 4;
        return new Result(State.WORKING, null, null);
    }
}

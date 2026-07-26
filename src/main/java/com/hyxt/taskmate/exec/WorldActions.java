package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.BlockInteraction;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** place / equip:放置方块、穿装备/持械。 */
final class WorldActions {

    private WorldActions() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "place", List.of("place_block"),
                "{\"action\":\"place\",\"block\":\"crafting_table\",\"desc\":\"...\"} —— 把背包中的方块放置在玩家旁边的地面上(用于放工作台/熔炉/箱子等)",
                false, PlaceHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "equip", List.of("wear", "hold"),
                "{\"action\":\"equip\",\"item\":\"iron_chestplate\",\"desc\":\"...\"} —— 盔甲会自动穿上;其他物品切换到手上",
                false, EquipHandler::new));
    }

    // ================================================= place

    static class PlaceHandler extends StepHandler {
        private String blockName;
        private Block block;
        private BlockPos placedAt;
        private int attempts;

        @Override
        public void start() {
            blockName = InventoryHelper.normalizeId(control.step().getString("block", ""));
            Identifier id = Identifier.of("minecraft", blockName);
            if (blockName.isBlank() || !Registries.BLOCK.containsId(id)) {
                control.fail("未知方块: " + blockName);
                return;
            }
            block = Registries.BLOCK.get(id);
            ClientPlayerEntity player = control.client().player;
            int slot = InventoryHelper.findSlot(player, blockName);
            if (slot < 0) {
                control.fail("背包里没有 " + blockName);
                return;
            }
            InventoryHelper.selectInHotbar(control.client(), slot);
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;
            // 已放置成功?
            if (placedAt != null && client.world.getBlockState(placedAt).getBlock() == block) {
                control.complete();
                return;
            }
            if (attempts++ > 10) {
                control.fail("找不到合适的位置放置 " + blockName + "(需要旁边有带实心顶面的空位)");
                return;
            }
            if (!InventoryHelper.matches(player.getInventory().getSelectedStack(), blockName)) {
                int slot = InventoryHelper.findSlot(player, blockName);
                if (slot < 0) {
                    control.fail("背包里没有 " + blockName);
                    return;
                }
                InventoryHelper.selectInHotbar(client, slot);
                return;
            }
            BlockPos spot = findPlacementSpot(client);
            if (spot == null) return; // 下个 tick 重试(attempts 会兜底)
            BlockPos support = spot.down();
            player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(spot));
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(support).add(0, 0.5, 0), Direction.UP, support, false);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            placedAt = spot;
        }

        /** 找玩家周围一圈:可替换的空位、其下方是实心方块、且在触及范围内 */
        private BlockPos findPlacementSpot(MinecraftClient client) {
            BlockPos base = client.player.getBlockPos();
            int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
            for (int[] off : offsets) {
                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos pos = base.add(off[0], dy, off[1]);
                    BlockPos below = pos.down();
                    if (client.world.getBlockState(pos).isReplaceable()
                            && client.world.getBlockState(below).isSolidBlock(client.world, below)
                            && BlockInteraction.distanceTo(client, pos) <= BlockInteraction.REACH) {
                        return pos;
                    }
                }
            }
            return null;
        }
    }

    // ================================================= equip

    static class EquipHandler extends StepHandler {
        private int waitTicks;
        private String item;
        private boolean armor;

        @Override
        public void start() {
            item = InventoryHelper.normalizeId(control.step().getString("item", ""));
            if (item.isBlank()) {
                control.fail("equip 缺少 item 参数");
                return;
            }
            ClientPlayerEntity player = control.client().player;
            int slot = InventoryHelper.findSlot(player, item);
            if (slot < 0) {
                control.fail("背包里没有 " + item);
                return;
            }
            armor = item.endsWith("_helmet") || item.endsWith("_chestplate")
                    || item.endsWith("_leggings") || item.endsWith("_boots")
                    || item.equals("elytra") || item.equals("turtle_helmet");
            if (armor) {
                // shift 点击自动穿戴(未打开容器时对 PlayerScreenHandler 操作)
                if (player.currentScreenHandler != player.playerScreenHandler) {
                    player.closeHandledScreen();
                }
                control.client().interactionManager.clickSlot(player.playerScreenHandler.syncId,
                        InventoryHelper.playerHandlerSlot(slot), 0,
                        net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
            } else {
                InventoryHelper.selectInHotbar(control.client(), slot);
            }
        }

        @Override
        public void tick() {
            if (waitTicks++ < 5) return;
            ClientPlayerEntity player = control.client().player;
            if (armor) {
                // 校验穿上了没(盔甲槽 36..39 是 PlayerInventory 的 armor 部分,直接查全背包即可)
                control.complete();
            } else {
                if (InventoryHelper.matches(player.getInventory().getSelectedStack(), item)) {
                    control.complete();
                } else {
                    control.fail("切换手持 " + item + " 失败");
                }
            }
        }
    }
}

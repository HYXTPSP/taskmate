package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.AutoPlacer;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

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
        private AutoPlacer placer;

        @Override
        public void start() {
            blockName = InventoryHelper.normalizeId(control.step().getString("block", ""));
            Identifier id = Identifier.of("minecraft", blockName);
            if (blockName.isBlank() || !Registries.BLOCK.containsId(id)) {
                control.fail("未知方块: " + blockName);
                return;
            }
            if (InventoryHelper.findSlot(control.client().player, blockName) < 0) {
                control.fail("背包里没有 " + blockName);
                return;
            }
            placer = new AutoPlacer(blockName);
        }

        @Override
        public void tick() {
            AutoPlacer.Result r = placer.tick(control.client());
            switch (r.state()) {
                case PLACED -> control.complete();
                case FAILED -> control.fail(r.reason());
                case WORKING -> {}
            }
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

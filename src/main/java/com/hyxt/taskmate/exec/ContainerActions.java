package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.ai.ContextStore;
import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.BlockInteraction;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/** withdraw / deposit:箱子存取(支持按记忆地点找箱子)。 */
final class ContainerActions {

    private ContainerActions() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "withdraw", List.of("take"),
                "{\"action\":\"withdraw\",\"items\":[\"iron_ingot\"],\"count\":8,\"name\":\"家的箱子\",\"desc\":\"...\"} —— 从箱子取物品;name 为已记住的地点(可省略,省略则用附近 8 格内最近的箱子);count 省略则全取",
                true, () -> new ChestHandler(true)));
        ActionRegistry.register(new ActionDefinition(
                "deposit", List.of("store"),
                "{\"action\":\"deposit\",\"items\":[\"cobblestone\",\"dirt\"],\"name\":\"家的箱子\",\"desc\":\"...\"} —— 把背包中指定物品存进箱子;items 省略则存入除工具/武器/盔甲/食物外的所有物品",
                true, () -> new ChestHandler(false)));
    }

    static class ChestHandler extends StepHandler {
        private enum Phase { LOCATE, GOTO, OPEN, TRANSFER, DONE }

        private final boolean withdraw;
        private Phase phase = Phase.LOCATE;
        private BlockPos chestPos;
        private List<String> items;
        private int wantCount;
        private int moved;
        private int cooldown;
        private int openTries;

        ChestHandler(boolean withdraw) {
            this.withdraw = withdraw;
        }

        @Override
        public void start() {
            items = control.step().getStringList("items").stream()
                    .map(InventoryHelper::normalizeId).toList();
            if (withdraw && items.isEmpty()) {
                control.fail("withdraw 需要 items 参数");
                return;
            }
            wantCount = control.step().getInt("count", Integer.MAX_VALUE);
            if (wantCount <= 0) wantCount = Integer.MAX_VALUE;
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;
            if (cooldown > 0) { cooldown--; return; }

            switch (phase) {
                case LOCATE -> {
                    String name = control.step().getString("name", "");
                    if (!name.isBlank()) {
                        ContextStore.MemoryEntry mem = ContextStore.findMemory(name);
                        if (mem == null) {
                            control.fail("没有名为「" + name + "」的记忆地点");
                            return;
                        }
                        BlockPos memPos = new BlockPos(mem.x(), mem.y(), mem.z());
                        if (BlockInteraction.distanceTo(client, memPos) > 6) {
                            phase = Phase.GOTO;
                            chestPos = memPos;
                            BaritoneBridge.goNear(memPos, 3);
                            return;
                        }
                    }
                    chestPos = BlockInteraction.findNearest(client,
                            Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL), 8);
                    if (chestPos == null) {
                        control.fail("附近 8 格内没有箱子/木桶");
                        return;
                    }
                    phase = BlockInteraction.inReach(client, chestPos) ? Phase.OPEN : Phase.GOTO;
                    if (phase == Phase.GOTO) BaritoneBridge.goNear(chestPos, 2);
                }
                case GOTO -> {
                    // 到了记忆点附近后,重新就近定位实际的箱子方块
                    BlockPos nearby = BlockInteraction.findNearest(client,
                            Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL), 5);
                    if (nearby != null && BlockInteraction.inReach(client, nearby)) {
                        chestPos = nearby;
                        BaritoneBridge.cancelAll();
                        phase = Phase.OPEN;
                    } else if (!BaritoneBridge.isCustomGoalActive() && !BaritoneBridge.isPathing()) {
                        if (nearby == null) {
                            control.fail("目的地附近没有找到箱子");
                        } else {
                            chestPos = nearby;
                            BaritoneBridge.goNear(nearby, 2);
                        }
                    }
                }
                case OPEN -> {
                    if (player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                        phase = Phase.TRANSFER;
                        return;
                    }
                    if (openTries++ > 5) {
                        control.fail("打不开箱子");
                        return;
                    }
                    BlockInteraction.interact(client, chestPos);
                    cooldown = 15;
                }
                case TRANSFER -> {
                    if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
                        control.fail("箱子界面被关闭");
                        return;
                    }
                    int containerSlots = handler.getRows() * 9;
                    if (moved >= wantCount) {
                        phase = Phase.DONE;
                        return;
                    }
                    if (withdraw) {
                        for (int slot = 0; slot < containerSlots; slot++) {
                            var stack = handler.getSlot(slot).getStack();
                            if (!stack.isEmpty() && matchesAny(stack, items)) {
                                moved += stack.getCount();
                                InventoryHelper.quickMove(client, handler, slot);
                                cooldown = 3;
                                return;
                            }
                        }
                    } else {
                        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
                            var stack = handler.getSlot(slot).getStack();
                            if (stack.isEmpty()) continue;
                            boolean shouldMove = items.isEmpty() ? shouldDeposit(stack) : matchesAny(stack, items);
                            if (shouldMove) {
                                moved += stack.getCount();
                                InventoryHelper.quickMove(client, handler, slot);
                                cooldown = 3;
                                return;
                            }
                        }
                    }
                    phase = Phase.DONE; // 没有可搬的了
                }
                case DONE -> {
                    if (player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                    }
                    if (withdraw && moved == 0) {
                        control.fail("箱子里没有找到 " + String.join("/", items));
                    } else {
                        com.hyxt.taskmate.util.ChatUi.info((withdraw ? "取出" : "存入") + "约 " + Math.min(moved, wantCount == Integer.MAX_VALUE ? moved : wantCount) + " 个物品");
                        control.complete();
                    }
                }
            }
        }

        private static boolean matchesAny(net.minecraft.item.ItemStack stack, List<String> names) {
            String id = InventoryHelper.idOf(stack);
            return names.stream().anyMatch(id::equals);
        }

        /** deposit 未指定 items 时:存杂物,保留工具/武器/盔甲/食物/火把 */
        private static boolean shouldDeposit(net.minecraft.item.ItemStack stack) {
            String id = InventoryHelper.idOf(stack);
            if (id.contains("sword") || id.contains("pickaxe") || id.contains("axe") || id.contains("shovel")
                    || id.contains("hoe") || id.contains("helmet") || id.contains("chestplate")
                    || id.contains("leggings") || id.contains("boots") || id.contains("shield")
                    || id.contains("bow") || id.equals("torch")) {
                return false;
            }
            return stack.get(net.minecraft.component.DataComponentTypes.FOOD) == null;
        }

        @Override
        public void onCancel() {
            ClientPlayerEntity player = control.client().player;
            if (player != null && player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
        }
    }
}

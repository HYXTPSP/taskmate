package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.BlockInteraction;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/** craft / smelt 动作:配方书驱动合成、熔炉全流程。 */
final class CraftingActions {

    private CraftingActions() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "craft", List.of("make"),
                "{\"action\":\"craft\",\"item\":\"iron_pickaxe\",\"count\":1,\"desc\":\"...\"} —— 合成物品直到背包中至少有 count 个(已够则跳过);材料必须已在背包;需要工作台时会自动放置背包里的工作台、或自动用木板先合一个,无需单独的 place 步骤;材料不足会失败并告知",
                true, CraftHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "smelt", List.of("cook"),
                "{\"action\":\"smelt\",\"input\":\"raw_iron\",\"count\":8,\"desc\":\"...\"} —— 把背包中的 input 烧炼并取回成品;附近没熔炉时会自动放置背包里的熔炉(背包也没有则失败,先 craft furnace);燃料用背包里的煤炭/木炭/木板",
                true, SmeltHandler::new));
    }

    // ================================================= craft

    static class CraftHandler extends StepHandler {
        private enum Phase { LOCATE, AUTO_CRAFT_TABLE, AUTO_PLACE_TABLE, GOTO_TABLE, OPEN_TABLE, CRAFT, DONE }

        private Phase phase = Phase.LOCATE;
        private String item;
        private int wantCount;
        private RecipeIndex.Found recipe;
        private RecipeIndex.Found tableRecipe;
        private com.hyxt.taskmate.util.AutoPlacer placer;
        private BlockPos tablePos;
        private int cooldown;
        private int emptyClicks;
        private int tableEmptyClicks;
        private int openTries;

        @Override
        public void start() {
            item = InventoryHelper.normalizeId(control.step().getString("item", ""));
            wantCount = Math.max(1, control.step().getInt("count", 1));
            if (item.isBlank()) {
                control.fail("craft 缺少 item 参数");
                return;
            }
            ClientPlayerEntity player = control.client().player;
            if (InventoryHelper.count(player, item) >= wantCount) {
                control.complete(); // 已经够了
                return;
            }
            recipe = RecipeIndex.findCrafting(control.client(), item);
            if (recipe == null) {
                control.fail("配方书中找不到 " + item + " 的合成配方(可能尚未解锁:先获得相关材料即可解锁,或该物品不是合成获得)");
            }
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;
            if (cooldown > 0) { cooldown--; return; }

            switch (phase) {
                case LOCATE -> {
                    if (!recipe.needsTable()) {
                        // 2x2:确保没有打开别的容器
                        if (player.currentScreenHandler != player.playerScreenHandler) {
                            player.closeHandledScreen();
                            cooldown = 5;
                            return;
                        }
                        phase = Phase.CRAFT;
                        return;
                    }
                    tablePos = BlockInteraction.findNearest(client, Set.of(Blocks.CRAFTING_TABLE), 6);
                    if (tablePos == null) {
                        // 自动兜底:背包有工作台→放;没有→有木板就先合成一个(2x2 配方)
                        if (InventoryHelper.findSlot(player, "crafting_table") >= 0) {
                            com.hyxt.taskmate.util.ChatUi.info("附近没有工作台,自动放置一个…");
                            placer = new com.hyxt.taskmate.util.AutoPlacer("crafting_table");
                            phase = Phase.AUTO_PLACE_TABLE;
                            return;
                        }
                        tableRecipe = RecipeIndex.findCrafting(client, "crafting_table");
                        if (tableRecipe != null && !tableRecipe.needsTable()) {
                            com.hyxt.taskmate.util.ChatUi.info("附近没有工作台,尝试用木板自动合成一个…");
                            phase = Phase.AUTO_CRAFT_TABLE;
                            return;
                        }
                        control.fail("附近 6 格内没有工作台,背包里也没有工作台或足够的木板(需要 4 块木板)");
                        return;
                    }
                    phase = BlockInteraction.inReach(client, tablePos) ? Phase.OPEN_TABLE : Phase.GOTO_TABLE;
                    if (phase == Phase.GOTO_TABLE) BaritoneBridge.goNear(tablePos, 2);
                }
                case AUTO_CRAFT_TABLE -> {
                    if (player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                        cooldown = 5;
                        return;
                    }
                    if (InventoryHelper.count(player, "crafting_table") > 0) {
                        placer = new com.hyxt.taskmate.util.AutoPlacer("crafting_table");
                        phase = Phase.AUTO_PLACE_TABLE;
                        return;
                    }
                    ItemStack out = player.currentScreenHandler.getSlot(0).getStack();
                    if (!out.isEmpty()) {
                        InventoryHelper.quickMove(client, player.currentScreenHandler, 0);
                        cooldown = 4;
                        return;
                    }
                    if (tableEmptyClicks++ >= 3) {
                        control.fail("无法自动合成工作台:木板不足(需要 4 块)");
                        return;
                    }
                    client.interactionManager.clickRecipe(player.currentScreenHandler.syncId,
                            tableRecipe.entry().id(), false);
                    cooldown = 6;
                }
                case AUTO_PLACE_TABLE -> {
                    var r = placer.tick(client);
                    switch (r.state()) {
                        case PLACED -> {
                            tablePos = r.pos();
                            openTries = 0;
                            phase = Phase.OPEN_TABLE;
                        }
                        case FAILED -> control.fail("自动放置工作台失败:" + r.reason());
                        case WORKING -> {}
                    }
                }
                case GOTO_TABLE -> {
                    if (BlockInteraction.inReach(client, tablePos)) {
                        BaritoneBridge.cancelAll();
                        phase = Phase.OPEN_TABLE;
                    } else if (!BaritoneBridge.isCustomGoalActive() && !BaritoneBridge.isPathing()) {
                        control.fail("无法走到工作台旁");
                    }
                }
                case OPEN_TABLE -> {
                    if (player.currentScreenHandler instanceof CraftingScreenHandler) {
                        phase = Phase.CRAFT;
                        return;
                    }
                    if (openTries++ > 5) {
                        control.fail("打不开工作台界面");
                        return;
                    }
                    BlockInteraction.interact(client, tablePos);
                    cooldown = 15;
                }
                case CRAFT -> {
                    ScreenHandler handler = player.currentScreenHandler;
                    boolean tableMode = handler instanceof CraftingScreenHandler;
                    if (recipe.needsTable() && !tableMode) { // 界面被关了,重新来
                        phase = Phase.LOCATE;
                        openTries = 0;
                        return;
                    }
                    if (InventoryHelper.count(player, item) >= wantCount) {
                        phase = Phase.DONE;
                        return;
                    }
                    ItemStack output = handler.getSlot(0).getStack();
                    if (!output.isEmpty()) {
                        InventoryHelper.quickMove(client, handler, 0);
                        emptyClicks = 0;
                        cooldown = 4;
                        return;
                    }
                    if (emptyClicks++ >= 3) {
                        control.fail("合成 " + item + " 失败:材料不足或无法自动摆放(已合成 "
                                + InventoryHelper.count(player, item) + "/" + wantCount + ")");
                        return;
                    }
                    client.interactionManager.clickRecipe(handler.syncId, recipe.entry().id(), false);
                    cooldown = 6;
                }
                case DONE -> {
                    if (player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                    }
                    control.complete();
                }
            }
        }

        @Override
        public void onCancel() {
            ClientPlayerEntity player = control.client().player;
            if (player != null && player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
        }
    }

    // ================================================= smelt

    static class SmeltHandler extends StepHandler {
        private enum Phase { LOCATE, AUTO_PLACE_FURNACE, GOTO, OPEN, LOAD, WAIT, TAKE, DONE }

        private com.hyxt.taskmate.util.AutoPlacer placer;

        private static final List<String> DEFAULT_FUELS = List.of("coal", "charcoal", "coal_block",
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
                "dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks");

        private Phase phase = Phase.LOCATE;
        private String input;
        private int wantCount;
        private BlockPos furnacePos;
        private int cooldown;
        private int openTries;
        private int startOutputHave; // 开始时背包里已有的产物数(用 input 推产物不可靠,直接按拿到的增量算)
        private int stallTicks;
        private int lastProgressSignature = -1;

        @Override
        public void start() {
            input = InventoryHelper.normalizeId(control.step().getString("input",
                    control.step().getString("item", "")));
            wantCount = Math.max(1, control.step().getInt("count", 1));
            if (input.isBlank()) {
                control.fail("smelt 缺少 input 参数");
                return;
            }
            ClientPlayerEntity player = control.client().player;
            if (InventoryHelper.count(player, input) <= 0) {
                control.fail("背包里没有 " + input + ",无法熔炼");
                return;
            }
            control.markOpenEnded("熔炼中…完成后自动取出,输入 #停止 可中断。");
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;
            if (cooldown > 0) { cooldown--; return; }

            switch (phase) {
                case LOCATE -> {
                    furnacePos = BlockInteraction.findNearest(client,
                            Set.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER), 6);
                    if (furnacePos == null) {
                        if (InventoryHelper.findSlot(player, "furnace") >= 0) {
                            com.hyxt.taskmate.util.ChatUi.info("附近没有熔炉,自动放置一个…");
                            placer = new com.hyxt.taskmate.util.AutoPlacer("furnace");
                            phase = Phase.AUTO_PLACE_FURNACE;
                            return;
                        }
                        control.fail("附近 6 格内没有熔炉,背包里也没有熔炉。请先 craft 一个 furnace(材料:8 圆石)");
                        return;
                    }
                    phase = BlockInteraction.inReach(client, furnacePos) ? Phase.OPEN : Phase.GOTO;
                    if (phase == Phase.GOTO) BaritoneBridge.goNear(furnacePos, 2);
                }
                case AUTO_PLACE_FURNACE -> {
                    var r = placer.tick(client);
                    switch (r.state()) {
                        case PLACED -> {
                            furnacePos = r.pos();
                            openTries = 0;
                            phase = Phase.OPEN;
                        }
                        case FAILED -> control.fail("自动放置熔炉失败:" + r.reason());
                        case WORKING -> {}
                    }
                }
                case GOTO -> {
                    if (BlockInteraction.inReach(client, furnacePos)) {
                        BaritoneBridge.cancelAll();
                        phase = Phase.OPEN;
                    } else if (!BaritoneBridge.isCustomGoalActive() && !BaritoneBridge.isPathing()) {
                        control.fail("无法走到熔炉旁");
                    }
                }
                case OPEN -> {
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                        phase = Phase.LOAD;
                        return;
                    }
                    if (openTries++ > 5) {
                        control.fail("打不开熔炉界面");
                        return;
                    }
                    BlockInteraction.interact(client, furnacePos);
                    cooldown = 15;
                }
                case LOAD -> {
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = Phase.LOCATE;
                        openTries = 0;
                        return;
                    }
                    // 装原料(shift 点会自动进原料格)
                    if (handler.getSlot(0).getStack().getCount() < wantCount
                            && InventoryHelper.count(player, input) > 0) {
                        int slot = findPlayerSlotInHandler(handler, input);
                        if (slot >= 0) {
                            InventoryHelper.quickMove(client, handler, slot);
                            cooldown = 4;
                            return;
                        }
                    }
                    // 装燃料
                    if (handler.getSlot(1).getStack().isEmpty()) {
                        String fuel = control.step().getString("fuel", "");
                        List<String> fuels = fuel.isBlank() ? DEFAULT_FUELS : List.of(InventoryHelper.normalizeId(fuel));
                        for (String f : fuels) {
                            int slot = findPlayerSlotInHandler(handler, f);
                            if (slot >= 0) {
                                InventoryHelper.quickMove(client, handler, slot);
                                cooldown = 4;
                                return;
                            }
                        }
                        if (handler.getSlot(0).getStack().isEmpty()) {
                            control.fail("没放进任何原料,且没有燃料");
                            return;
                        }
                        control.fail("背包里没有燃料(煤炭/木炭/木板等)");
                        return;
                    }
                    startOutputHave = 0;
                    phase = Phase.WAIT;
                }
                case WAIT -> {
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = Phase.LOCATE;
                        openTries = 0;
                        return;
                    }
                    ItemStack out = handler.getSlot(2).getStack();
                    int collected = totalGained();
                    if (collected + out.getCount() >= wantCount
                            || (handler.getSlot(0).getStack().isEmpty() && !out.isEmpty())) {
                        phase = Phase.TAKE;
                        return;
                    }
                    // 停滞检测:输入还有但既不燃烧也无进度变化
                    int signature = out.getCount() * 1000 + handler.getSlot(0).getStack().getCount() * 10
                            + (handler.isBurning() ? 1 : 0);
                    if (signature == lastProgressSignature) {
                        if (!handler.isBurning() && ++stallTicks > 200) {
                            if (!out.isEmpty()) { phase = Phase.TAKE; return; }
                            control.fail("熔炉停止工作(燃料耗尽?已获得 " + collected + "/" + wantCount + ")");
                            return;
                        }
                    } else {
                        stallTicks = 0;
                        lastProgressSignature = signature;
                    }
                    cooldown = 10;
                }
                case TAKE -> {
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = Phase.LOCATE;
                        openTries = 0;
                        return;
                    }
                    if (!handler.getSlot(2).getStack().isEmpty()) {
                        InventoryHelper.quickMove(client, handler, 2);
                        cooldown = 4;
                        return;
                    }
                    if (totalGained() >= wantCount || handler.getSlot(0).getStack().isEmpty()) {
                        phase = Phase.DONE;
                    } else {
                        phase = Phase.WAIT;
                    }
                }
                case DONE -> {
                    if (player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                    }
                    int collected = totalGained();
                    if (collected <= 0) {
                        control.fail("熔炼没有产出");
                    } else {
                        control.complete();
                    }
                }
            }
        }

        /** 本步骤以来背包净增量总数(近似产物数) */
        private int totalGained() {
            return control.gainsSoFar().values().stream().mapToInt(Integer::intValue).sum();
        }

        /** 在当前容器 handler 中找玩家背包区里装有指定物品的槽位 id */
        private int findPlayerSlotInHandler(ScreenHandler handler, String itemName) {
            // 熔炉容器槽 0..2,玩家区从 3 开始
            for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
                if (InventoryHelper.matches(handler.getSlot(slotId).getStack(), itemName)) return slotId;
            }
            return -1;
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

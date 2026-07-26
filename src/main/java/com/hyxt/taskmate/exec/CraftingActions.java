package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.AutoPlacer;
import com.hyxt.taskmate.util.BlockInteraction;
import com.hyxt.taskmate.util.ChatUi;
import com.hyxt.taskmate.util.InventoryHelper;
import com.hyxt.taskmate.util.TaskLog;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * craft / smelt 动作。
 * craft 带递归材料解析:缺木板→自动用原木合、缺木棍→自动用木板合、
 * 需要工作台→自动放置/自动先合一个,只有缺真正的原材料才失败上报 AI。
 */
final class CraftingActions {

    private CraftingActions() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "craft", List.of("make"),
                "{\"action\":\"craft\",\"item\":\"iron_pickaxe\",\"count\":1,\"desc\":\"...\"} —— 合成物品直到背包中至少有 count 个(count 是最终总数,已够则跳过);会自动递归合成缺失的中间材料(木板/木棍/工作台等)并自动放置工作台,只要基础原料(原木/圆石/铁锭等)在背包里即可,无需列出中间步骤;缺基础原料才会失败并告知缺什么",
                true, CraftHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "smelt", List.of("cook"),
                "{\"action\":\"smelt\",\"input\":\"raw_iron\",\"count\":8,\"desc\":\"...\"} —— 把背包中的 input 烧炼并取回成品;附近没熔炉时会自动放置背包里的熔炉(背包也没有则失败,先 craft furnace);燃料用背包里的煤炭/木炭/木板",
                true, SmeltHandler::new));
    }

    // ================================================= craft

    static class CraftHandler extends StepHandler {
        private enum Phase { CRAFT, TABLE_LOCATE, GOTO_TABLE, OPEN_TABLE, AUTO_PLACE_TABLE, DONE }

        /** 合成任务栈:item 合成到 ensure 个(最终总数) */
        private record Job(String item, int ensure, RecipeIndex.Found recipe) {}

        private final Deque<Job> jobs = new ArrayDeque<>();
        private Phase phase = Phase.CRAFT;
        private AutoPlacer placer;
        private BlockPos tablePos;
        private int cooldown;
        private int emptyClicks;
        private int openTries;
        private boolean openedTable;

        @Override
        public void start() {
            String item = InventoryHelper.normalizeId(control.step().getString("item", ""));
            int want = Math.max(1, control.step().getInt("count", 1));
            if (item.isBlank()) {
                control.fail("craft 缺少 item 参数");
                return;
            }
            ClientPlayerEntity player = control.client().player;
            if (InventoryHelper.count(player, item) >= want) {
                control.complete();
                return;
            }
            RecipeIndex.Found recipe = RecipeIndex.findCrafting(control.client(), item);
            if (recipe == null) {
                control.fail("配方书中找不到 " + item + " 的合成配方(可能尚未解锁:先获得相关材料即可解锁,或该物品不是合成获得)");
                return;
            }
            jobs.push(new Job(item, want, recipe));
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;
            if (cooldown > 0) { cooldown--; return; }

            switch (phase) {
                case CRAFT -> {
                    if (jobs.isEmpty()) {
                        phase = Phase.DONE;
                        return;
                    }
                    Job top = jobs.peek();
                    if (InventoryHelper.count(player, top.item()) >= top.ensure()) {
                        jobs.pop();
                        emptyClicks = 0;
                        TaskLog.log("craft: " + top.item() + " 达到 " + top.ensure() + ",出栈");
                        return;
                    }
                    boolean tableOpen = player.currentScreenHandler instanceof CraftingScreenHandler;
                    if (top.recipe().needsTable() && !tableOpen) {
                        phase = Phase.TABLE_LOCATE;
                        return;
                    }
                    if (!tableOpen && player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen(); // 关掉无关界面
                        cooldown = 5;
                        return;
                    }
                    ScreenHandler handler = player.currentScreenHandler;
                    ItemStack output = handler.getSlot(0).getStack();
                    if (!output.isEmpty()) {
                        InventoryHelper.quickMove(client, handler, 0);
                        emptyClicks = 0;
                        cooldown = 4;
                        return;
                    }
                    // 缺料检查 + 递归子合成
                    List<RecipeIndex.Missing> missing = RecipeIndex.computeMissing(client, top.recipe());
                    if (!missing.isEmpty()) {
                        RecipeIndex.Missing m = missing.get(0);
                        if (jobs.size() >= 5) {
                            control.fail("合成 " + top.item() + " 的材料链太深,缺少: " + describe(missing));
                            return;
                        }
                        boolean looping = jobs.stream().anyMatch(j -> j.item().equals(m.item()));
                        RecipeIndex.Found sub = looping ? null : RecipeIndex.findCrafting(client, m.item());
                        if (sub == null) {
                            control.fail("合成 " + top.item() + " 缺少材料: " + describe(missing)
                                    + "(无法自动合成,需要先采集)");
                            return;
                        }
                        int ensure = InventoryHelper.count(player, m.item()) + m.count();
                        ChatUi.info("缺少 " + m.item() + "×" + m.count() + ",自动先合成…");
                        TaskLog.log("craft: 递归合成 " + m.item() + " -> " + ensure);
                        jobs.push(new Job(m.item(), ensure, sub));
                        return;
                    }
                    if (emptyClicks++ >= 3) {
                        control.fail("合成 " + top.item() + " 失败:配方无法自动摆放(材料在但摆放被拒绝)");
                        return;
                    }
                    client.interactionManager.clickRecipe(handler.syncId, top.recipe().entry().id(), false);
                    cooldown = 6;
                }
                case TABLE_LOCATE -> {
                    tablePos = BlockInteraction.findNearest(client, Set.of(Blocks.CRAFTING_TABLE), 6);
                    if (tablePos == null) {
                        if (InventoryHelper.findSlot(player, "crafting_table") >= 0) {
                            ChatUi.info("附近没有工作台,自动放置一个…");
                            placer = new AutoPlacer("crafting_table");
                            phase = Phase.AUTO_PLACE_TABLE;
                            return;
                        }
                        RecipeIndex.Found tr = RecipeIndex.findCrafting(client, "crafting_table");
                        if (tr == null || tr.needsTable()
                                || jobs.stream().anyMatch(j -> j.item().equals("crafting_table"))) {
                            control.fail("附近没有工作台,背包里也没有工作台或足够的材料自动合成(需要 4 块木板)");
                            return;
                        }
                        ChatUi.info("附近没有工作台,自动先合成一个…");
                        jobs.push(new Job("crafting_table",
                                InventoryHelper.count(player, "crafting_table") + 1, tr));
                        phase = Phase.CRAFT;
                        return;
                    }
                    openTries = 0;
                    phase = BlockInteraction.inReach(client, tablePos) ? Phase.OPEN_TABLE : Phase.GOTO_TABLE;
                    if (phase == Phase.GOTO_TABLE) BaritoneBridge.goNear(tablePos, 2);
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
                        openedTable = true;
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
                case AUTO_PLACE_TABLE -> {
                    AutoPlacer.Result r = placer.tick(client);
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
                case DONE -> {
                    if (openedTable && player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                    }
                    control.complete();
                }
            }
        }

        private static String describe(List<RecipeIndex.Missing> missing) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(missing.get(i).item()).append("×").append(missing.get(i).count());
            }
            return sb.toString();
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

        private static final List<String> DEFAULT_FUELS = List.of("coal", "charcoal", "coal_block",
                "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
                "dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks");

        private AutoPlacer placer;
        private Phase phase = Phase.LOCATE;
        private String input;
        private int wantCount;
        private BlockPos furnacePos;
        private int cooldown;
        private int openTries;
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
                            ChatUi.info("附近没有熔炉,自动放置一个…");
                            placer = new AutoPlacer("furnace");
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
                    AutoPlacer.Result r = placer.tick(client);
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
                    if (handler.getSlot(0).getStack().getCount() < wantCount
                            && InventoryHelper.count(player, input) > 0) {
                        int slot = findPlayerSlotInHandler(handler, input);
                        if (slot >= 0) {
                            InventoryHelper.quickMove(client, handler, slot);
                            cooldown = 4;
                            return;
                        }
                    }
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
                    int signature = out.getCount() * 1000 + handler.getSlot(0).getStack().getCount() * 10
                            + (handler.isBurning() ? 1 : 0);
                    if (signature == lastProgressSignature) {
                        if (!handler.isBurning() && ++stallTicks > 200) {
                            if (!out.isEmpty()) { phase = Phase.TAKE; return; }
                            retrieveAll(handler);
                            control.fail("熔炉停止工作(燃料耗尽?已获得 " + collected + "/" + wantCount + ",炉内物品已取回)");
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
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler) {
                        // 把没烧完的原料和多余燃料拿回来,避免物品滞留在熔炉里丢失
                        if (!handler.getSlot(0).getStack().isEmpty() || !handler.getSlot(2).getStack().isEmpty()) {
                            retrieveAll(handler);
                        }
                        player.closeHandledScreen();
                    } else if (player.currentScreenHandler != player.playerScreenHandler) {
                        player.closeHandledScreen();
                    }
                    if (totalGained() <= 0) {
                        control.fail("熔炼没有产出");
                    } else {
                        control.complete();
                    }
                }
            }
        }

        private void retrieveAll(AbstractFurnaceScreenHandler handler) {
            MinecraftClient client = control.client();
            for (int s = 0; s <= 2; s++) {
                if (!handler.getSlot(s).getStack().isEmpty()) {
                    InventoryHelper.quickMove(client, handler, s);
                }
            }
        }

        private int totalGained() {
            return control.gainsSoFar().values().stream().mapToInt(Integer::intValue).sum();
        }

        private int findPlayerSlotInHandler(ScreenHandler handler, String itemName) {
            for (int slotId = 3; slotId < handler.slots.size(); slotId++) {
                if (InventoryHelper.matches(handler.getSlot(slotId).getStack(), itemName)) return slotId;
            }
            return -1;
        }

        @Override
        public void onCancel() {
            ClientPlayerEntity player = control.client().player;
            if (player == null) return;
            // 中断时尽量把炉子里的东西拿回来,防止物品"失踪"
            if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler) {
                retrieveAll(handler);
                player.closeHandledScreen();
            } else if (player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
        }
    }
}

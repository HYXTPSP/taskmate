package com.hyxt.taskmate.exec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.api.SubStep;
import com.hyxt.taskmate.util.ChatUi;
import com.hyxt.taskmate.util.InventoryHelper;
import com.hyxt.taskmate.util.TaskLog;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旗舰技能 obtain:"搞到任何东西"。
 * 本地递归解决整条获取链:工具阶梯(木镐→石镐→铁镐→钻石镐)、采集、熔炼、合成。
 * 例:obtain diamond → 没铁镐→obtain iron_pickaxe → 没铁锭→smelt raw_iron →
 *     没生铁→用石镐挖铁矿 → 没石镐→…全部自动,不消耗 token。
 */
final class ObtainAction {

    private ObtainAction() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "obtain", List.of("get", "acquire"),
                "{\"action\":\"obtain\",\"item\":\"diamond\",\"count\":3,\"desc\":\"...\"} —— 【首选动作】获取任何物品到指定总数:自动解决整条链(挖矿工具阶梯、采集、熔炼、合成、工作台/熔炉),涉及\"搞到某物品\"的需求一律优先用它,不要手动拆链",
                true, ObtainHandler::new));
    }

    // ---- 知识表 ----

    private static final List<String> LOGS = List.of("oak_log", "birch_log", "spruce_log", "jungle_log",
            "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log");
    private static final List<String> PICKAXES = List.of("wooden_pickaxe", "stone_pickaxe",
            "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe");

    /** 可直接采集的物品:目标方块 + 所需镐等级(0=徒手,1=木镐,2=石镐,3=铁镐,4=钻石镐) */
    private record MineSource(List<String> blocks, int tier) {}

    private static final Map<String, MineSource> MINE_SOURCES = Map.ofEntries(
            Map.entry("cobblestone", new MineSource(List.of("stone", "cobblestone"), 1)),
            Map.entry("coal", new MineSource(List.of("coal_ore", "deepslate_coal_ore"), 1)),
            Map.entry("raw_iron", new MineSource(List.of("iron_ore", "deepslate_iron_ore"), 2)),
            Map.entry("raw_copper", new MineSource(List.of("copper_ore", "deepslate_copper_ore"), 2)),
            Map.entry("lapis_lazuli", new MineSource(List.of("lapis_ore", "deepslate_lapis_ore"), 2)),
            Map.entry("raw_gold", new MineSource(List.of("gold_ore", "deepslate_gold_ore"), 3)),
            Map.entry("diamond", new MineSource(List.of("diamond_ore", "deepslate_diamond_ore"), 3)),
            Map.entry("redstone", new MineSource(List.of("redstone_ore", "deepslate_redstone_ore"), 3)),
            Map.entry("emerald", new MineSource(List.of("emerald_ore", "deepslate_emerald_ore"), 3)),
            Map.entry("obsidian", new MineSource(List.of("obsidian"), 4)),
            Map.entry("sand", new MineSource(List.of("sand"), 0)),
            Map.entry("gravel", new MineSource(List.of("gravel"), 0)),
            Map.entry("dirt", new MineSource(List.of("dirt", "grass_block"), 0))
    );

    /** 熔炼获得的物品:原料 */
    private static final Map<String, String> SMELT_SOURCES = Map.of(
            "iron_ingot", "raw_iron",
            "copper_ingot", "raw_copper",
            "gold_ingot", "raw_gold",
            "glass", "sand",
            "stone", "cobblestone"
    );

    private static final Pattern MISSING_PATTERN = Pattern.compile("缺少材料: ([a-z0-9_]+)×(\\d+)");

    /** 常用配方知识表:不依赖配方书解锁。材料齐了配方自然解锁,再走 craft */
    private record Ing(String item, int count) {}

    private static final Map<String, List<Ing>> CRAFT_KNOWLEDGE = buildKnowledge();

    private static Map<String, List<Ing>> buildKnowledge() {
        Map<String, List<Ing>> m = new java.util.HashMap<>();
        String[][] tiers = {{"wooden", "planks"}, {"stone", "cobblestone"},
                {"iron", "iron_ingot"}, {"golden", "gold_ingot"}, {"diamond", "diamond"}};
        for (String[] t : tiers) {
            m.put(t[0] + "_pickaxe", List.of(new Ing(t[1], 3), new Ing("stick", 2)));
            m.put(t[0] + "_axe", List.of(new Ing(t[1], 3), new Ing("stick", 2)));
            m.put(t[0] + "_sword", List.of(new Ing(t[1], 2), new Ing("stick", 1)));
            m.put(t[0] + "_shovel", List.of(new Ing(t[1], 1), new Ing("stick", 2)));
            m.put(t[0] + "_hoe", List.of(new Ing(t[1], 2), new Ing("stick", 2)));
        }
        String[][] armors = {{"iron", "iron_ingot"}, {"golden", "gold_ingot"},
                {"diamond", "diamond"}, {"leather", "leather"}};
        for (String[] a : armors) {
            m.put(a[0] + "_helmet", List.of(new Ing(a[1], 5)));
            m.put(a[0] + "_chestplate", List.of(new Ing(a[1], 8)));
            m.put(a[0] + "_leggings", List.of(new Ing(a[1], 7)));
            m.put(a[0] + "_boots", List.of(new Ing(a[1], 4)));
        }
        m.put("crafting_table", List.of(new Ing("planks", 4)));
        m.put("furnace", List.of(new Ing("cobblestone", 8)));
        m.put("chest", List.of(new Ing("planks", 8)));
        m.put("stick", List.of(new Ing("planks", 2)));
        m.put("torch", List.of(new Ing("coal", 1), new Ing("stick", 1)));
        m.put("shield", List.of(new Ing("planks", 6), new Ing("iron_ingot", 1)));
        m.put("bucket", List.of(new Ing("iron_ingot", 3)));
        return m;
    }

    // ---- 实现 ----

    static class ObtainHandler extends StepHandler {
        private enum ChildKind { NONE, CRAFT, MINE, SMELT, OBTAIN }

        private String item;
        private int want;
        private int depth;
        private String chain;
        private SubStep child;
        private ChildKind childKind = ChildKind.NONE;
        private int craftRetries;

        @Override
        public void start() {
            item = InventoryHelper.normalizeId(control.step().getString("item", ""));
            want = Math.max(1, control.step().getInt("count", 1));
            depth = control.step().getInt("_depth", 0);
            chain = control.step().getString("_chain", "");
            if (item.isBlank()) {
                control.fail("obtain 缺少 item 参数");
                return;
            }
            if (depth > 8) {
                control.fail("obtain 链太深: " + chain);
                return;
            }
            if (chain.contains("|" + item + "|")) {
                control.fail("obtain 链出现循环: " + chain + item);
                return;
            }
            if (depth == 0) {
                control.markOpenEnded("obtain " + item + "×" + want + " 链执行中,输入 #停止 可中断。");
            }
        }

        @Override
        public void tick() {
            ClientPlayerEntity player = control.client().player;

            if (child != null) {
                switch (child.tick(control.client())) {
                    case RUNNING -> { return; }
                    case DONE -> {
                        child = null;
                        childKind = ChildKind.NONE;
                        return; // 下 tick 重新评估
                    }
                    case FAILED -> {
                        String reason = child.failReason();
                        ChildKind kind = childKind;
                        child = null;
                        childKind = ChildKind.NONE;
                        // craft 因缺基础材料失败 → 递归 obtain 该材料后重试
                        if (kind == ChildKind.CRAFT) {
                            Matcher m = MISSING_PATTERN.matcher(reason);
                            if (m.find() && craftRetries++ < 4) {
                                String need = m.group(1);
                                int cnt = Integer.parseInt(m.group(2));
                                spawnObtain(need, InventoryHelper.count(player, need) + cnt);
                                return;
                            }
                        }
                        control.fail("obtain " + item + " 失败: " + reason);
                        return;
                    }
                }
            }

            if (InventoryHelper.count(player, item) >= want) {
                control.complete();
                return;
            }
            decide(player);
        }

        private void decide(ClientPlayerEntity player) {
            // 1) 原木类:通配所有原木
            if (item.endsWith("_log")) {
                spawnMine(LOGS, want);
                return;
            }
            // 2) 直接采集
            MineSource src = MINE_SOURCES.get(item);
            if (src != null) {
                if (src.tier() > 0 && !hasPickaxeTier(player, src.tier())) {
                    String tool = PICKAXES.get(src.tier() - 1);
                    ChatUi.info("挖 " + item + " 需要 " + tool + ",先去搞一把…");
                    spawnObtain(tool, 1);
                    return;
                }
                spawnMine(src.blocks(), want);
                return;
            }
            // 3) 熔炼
            String smeltInput = SMELT_SOURCES.get(item);
            if (smeltInput != null) {
                int need = want - InventoryHelper.count(player, item);
                if (InventoryHelper.count(player, smeltInput) < need) {
                    spawnObtain(smeltInput, need);
                    return;
                }
                if (!hasAnyFuel(player)) {
                    ChatUi.info("没有燃料,先去搞点煤炭…");
                    spawnObtain("coal", Math.max(1, (need + 7) / 8));
                    return;
                }
                // 熔炉:smelt 自己会放背包里的;背包也没有就先搞一个
                if (InventoryHelper.count(player, "furnace") <= 0
                        && com.hyxt.taskmate.util.BlockInteraction.findNearest(control.client(),
                        java.util.Set.of(net.minecraft.block.Blocks.FURNACE,
                                net.minecraft.block.Blocks.BLAST_FURNACE,
                                net.minecraft.block.Blocks.SMOKER), 6) == null) {
                    spawnObtain("furnace", 1);
                    return;
                }
                JsonObject args = new JsonObject();
                args.addProperty("input", smeltInput);
                args.addProperty("count", need);
                spawn("smelt", args, ChildKind.SMELT);
                return;
            }
            // 4) 配方知识表(不依赖配方书解锁):先递归备齐材料,再合成
            List<Ing> ings = CRAFT_KNOWLEDGE.get(item);
            if (ings != null) {
                int crafts = want - InventoryHelper.count(player, item);
                for (Ing ing : ings) {
                    int needTotal = ing.count() * crafts;
                    if (countFlex(player, ing.item()) < needTotal) {
                        acquireFlex(player, ing.item(), needTotal);
                        return;
                    }
                }
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                args.addProperty("count", want);
                spawn("craft", args, ChildKind.CRAFT);
                return;
            }
            // 5) 配方书里已解锁的其他配方
            if (RecipeIndex.findCrafting(control.client(), item) != null) {
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                args.addProperty("count", want);
                spawn("craft", args, ChildKind.CRAFT);
                return;
            }
            control.fail("不知道如何获取 " + item + "(不在知识表里也不可合成),请手动规划");
        }

        /** "planks" 是伪物品:任意木板都算 */
        private int countFlex(ClientPlayerEntity player, String name) {
            if (name.equals("planks")) {
                int total = 0;
                for (int i = 0; i < player.getInventory().size(); i++) {
                    var s = player.getInventory().getStack(i);
                    if (!s.isEmpty() && InventoryHelper.idOf(s).endsWith("_planks")) total += s.getCount();
                }
                return total;
            }
            return InventoryHelper.count(player, name);
        }

        private void acquireFlex(ClientPlayerEntity player, String name, int needTotal) {
            if (!name.equals("planks")) {
                spawnObtain(name, needTotal);
                return;
            }
            // 木板:背包有原木就合对应木板;没有就先去砍树
            String logId = null;
            for (String log : LOGS) {
                if (InventoryHelper.count(player, log) > 0) {
                    logId = log;
                    break;
                }
            }
            int planksHave = countFlex(player, "planks");
            if (logId != null) {
                String planks = logId.replace("_log", "_planks");
                JsonObject args = new JsonObject();
                args.addProperty("item", planks);
                args.addProperty("count", InventoryHelper.count(player, planks) + (needTotal - planksHave));
                spawn("craft", args, ChildKind.CRAFT);
            } else {
                int logsNeed = (needTotal - planksHave + 3) / 4;
                int logsHave = 0;
                for (String log : LOGS) logsHave += InventoryHelper.count(player, log);
                ChatUi.info("缺木板,先去砍树…");
                spawnMine(LOGS, logsHave + logsNeed);
            }
        }

        private void spawnMine(List<String> blocks, int totalWant) {
            JsonObject args = new JsonObject();
            JsonArray arr = new JsonArray();
            blocks.forEach(arr::add);
            args.add("blocks", arr);
            args.addProperty("count", totalWant);
            spawn("mine", args, ChildKind.MINE);
        }

        private void spawnObtain(String subItem, int totalWant) {
            JsonObject args = new JsonObject();
            args.addProperty("item", subItem);
            args.addProperty("count", totalWant);
            args.addProperty("_depth", depth + 1);
            args.addProperty("_chain", chain + "|" + item + "|");
            spawn("obtain", args, ChildKind.OBTAIN);
        }

        private void spawn(String action, JsonObject args, ChildKind kind) {
            TaskLog.log("obtain[" + item + "] -> " + action + " " + args);
            child = SubStep.create(action, args, control);
            childKind = kind;
        }

        private static boolean hasPickaxeTier(ClientPlayerEntity player, int tier) {
            for (int t = tier - 1; t < PICKAXES.size(); t++) {
                if (InventoryHelper.count(player, PICKAXES.get(t)) > 0) return true;
            }
            return false;
        }

        private static boolean hasAnyFuel(ClientPlayerEntity player) {
            return InventoryHelper.findSlot(player, s -> {
                String id = InventoryHelper.idOf(s);
                return id.equals("coal") || id.equals("charcoal") || id.equals("coal_block")
                        || id.endsWith("_planks");
            }) >= 0;
        }

        @Override
        public void onCancel() {
            if (child != null) child.cancel();
        }
    }
}

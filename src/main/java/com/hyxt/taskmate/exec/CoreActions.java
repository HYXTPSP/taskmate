package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.ai.ContextStore;
import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.ChatUi;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

/** 核心动作,全部通过 ActionRegistry 注册(与插件动作同一套机制)。 */
final class CoreActions {

    private CoreActions() {}

    static void registerAll() {
        ActionRegistry.register(new ActionDefinition(
                "goto", List.of("go_to", "move", "walk"),
                "{\"action\":\"goto\",\"x\":100,\"y\":64,\"z\":-200,\"desc\":\"...\"} —— 走到指定坐标",
                true, GotoHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "goto_block", List.of("go_to_block", "find_block"),
                "{\"action\":\"goto_block\",\"block\":\"crafting_table\",\"desc\":\"...\"} —— 寻找并走到最近的指定方块(注册名不带 minecraft: 前缀)",
                true, GotoBlockHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "mine", List.of("collect", "dig", "harvest", "chop"),
                "{\"action\":\"mine\",\"blocks\":[\"oak_log\"],\"count\":20,\"desc\":\"...\"} —— 采集方块直到获得 count 个物品(count 省略则持续采集直到玩家停止)",
                true, MineHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "follow", List.of("follow_player"),
                "{\"action\":\"follow\",\"name\":\"玩家名\",\"desc\":\"...\"} —— 持续跟随某玩家(直到玩家手动停止)",
                true, FollowHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "wait", List.of("sleep"),
                "{\"action\":\"wait\",\"seconds\":5,\"desc\":\"...\"} —— 原地等待",
                false, WaitHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "say", List.of("chat"),
                "{\"action\":\"say\",\"text\":\"...\",\"desc\":\"...\"} —— 在聊天栏发言",
                false, SayHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "stop", List.of(),
                "{\"action\":\"stop\",\"desc\":\"...\"} —— 停止一切动作",
                false, StopHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "collect", List.of("pickup", "collect_items"),
                "{\"action\":\"collect\",\"radius\":16,\"desc\":\"...\"} —— 自动走过去捡起附近地上的所有掉落物(死亡后回收装备、采集后清场用),直到捡完为止",
                true, CollectHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "remember", List.of("mark"),
                "{\"action\":\"remember\",\"name\":\"家\",\"note\":\"可选备注\",\"desc\":\"...\"} —— 把玩家当前位置记为地点(跨会话保存,已记住的地点见状态快照)",
                false, RememberHandler::new));
        ActionRegistry.register(new ActionDefinition(
                "forget", List.of(),
                "{\"action\":\"forget\",\"name\":\"家\",\"desc\":\"...\"} —— 删除一个已记住的地点",
                false, ForgetHandler::new));
    }

    // ------------------------------------------------ 工具

    static String normalizeId(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring("minecraft:".length()) : n;
    }

    static Block resolveBlock(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.of("minecraft", normalizeId(name));
        if (!Registries.BLOCK.containsId(id)) return null;
        return Registries.BLOCK.get(id);
    }

    // ------------------------------------------------ 各动作实现

    static class GotoHandler extends StepHandler {
        private BlockPos target;

        @Override
        public void start() {
            if (!control.step().has("x") || !control.step().has("y") || !control.step().has("z")) {
                control.fail("goto 缺少 x/y/z 参数");
                return;
            }
            target = new BlockPos(control.step().getInt("x", 0),
                    control.step().getInt("y", 0), control.step().getInt("z", 0));
            BaritoneBridge.goTo(target.getX(), target.getY(), target.getZ());
        }

        @Override
        public void tick() {
            if (!BaritoneBridge.isCustomGoalActive() && !BaritoneBridge.isPathing()) {
                double dist = Math.sqrt(control.client().player.getBlockPos().getSquaredDistance(target));
                if (dist <= 4.5) {
                    control.complete();
                } else {
                    control.fail("未能到达目标坐标(距离还有 " + String.format("%.0f", dist) + " 格)");
                }
            }
        }
    }

    static class GotoBlockHandler extends StepHandler {
        private Block block;
        private String name;

        @Override
        public void start() {
            name = normalizeId(control.step().getString("block", ""));
            block = resolveBlock(name);
            if (block == null) {
                control.fail("未知方块: " + name);
                return;
            }
            BaritoneBridge.goToBlock(block);
        }

        @Override
        public void tick() {
            if (!BaritoneBridge.isGetToBlockActive() && !BaritoneBridge.isPathing()) {
                // 校验:玩家附近是否真的有目标方块,防止"找不到→静默放弃"被误判为完成
                if (isBlockNearby(block, 8)) {
                    control.complete();
                } else {
                    control.fail("附近未找到方块 " + name + ",这一带可能不存在该方块");
                }
            }
        }

        private boolean isBlockNearby(Block target, int radius) {
            BlockPos center = control.client().player.getBlockPos();
            for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
                if (control.client().world.getBlockState(pos).getBlock() == target) return true;
            }
            return false;
        }
    }

    static class MineHandler extends StepHandler {
        private int count;

        @Override
        public void start() {
            List<String> names = new java.util.ArrayList<>(control.step().getStringList("blocks").stream()
                    .map(CoreActions::normalizeId).toList());
            if (names.isEmpty() && control.step().has("block")) { // 容错:AI 常把 blocks 写成 block
                names.add(normalizeId(control.step().getString("block", "")));
            }
            if (names.isEmpty() || names.get(0).isBlank()) {
                control.fail("mine 缺少 blocks 参数(数组)");
                return;
            }
            for (String n : names) {
                if (resolveBlock(n) == null) {
                    control.fail("未知方块: " + n);
                    return;
                }
            }
            count = control.step().getInt("count", 0);
            if (count <= 0) {
                control.markOpenEnded("该步骤未限定数量,将持续采集,输入 #停止 可结束。");
            }
            BaritoneBridge.mine(count, names.toArray(new String[0]));
        }

        @Override
        public void tick() {
            if (!BaritoneBridge.isMineActive()) {
                int gained = control.gainsSoFar().values().stream().mapToInt(Integer::intValue).sum();
                if (count > 0 && gained == 0) {
                    control.fail("未采集到任何物品,附近可能没有目标方块");
                } else {
                    control.complete();
                }
            }
        }
    }

    static class FollowHandler extends StepHandler {
        @Override
        public void start() {
            String name = control.step().getString("name", "");
            if (name.isBlank()) {
                control.fail("follow 缺少 name 参数");
                return;
            }
            control.markOpenEnded("开始跟随 " + name + ",输入 #停止 可结束。");
            BaritoneBridge.follow(name);
        }
    }

    static class WaitHandler extends StepHandler {
        @Override
        public void start() {}

        @Override
        public void tick() {
            if (control.ticks() >= control.step().getInt("seconds", 1) * 20L) {
                control.complete();
            }
        }
    }

    static class SayHandler extends StepHandler {
        @Override
        public void start() {
            String text = control.step().getString("text", "").trim();
            while (text.startsWith("#")) text = text.substring(1); // 防止自触发
            if (!text.isBlank() && control.client().getNetworkHandler() != null) {
                control.client().getNetworkHandler().sendChatMessage(text);
            }
            control.complete();
        }
    }

    static class StopHandler extends StepHandler {
        @Override
        public void start() {
            if (BaritoneCheck.available()) BaritoneBridge.cancelAll();
            control.complete();
        }
    }

    static class CollectHandler extends StepHandler {
        private int repathCooldown;
        private int idleTicks;
        private com.hyxt.taskmate.api.SubStep redirect;

        @Override
        public void start() {
            // 容错:AI 有时用 collect 采集方块 → 自动转成 mine
            if (control.step().has("blocks") || control.step().has("block")) {
                redirect = com.hyxt.taskmate.api.SubStep.create("mine", control.step().args, control);
            }
        }

        @Override
        public void tick() {
            if (redirect != null) {
                switch (redirect.tick(control.client())) {
                    case DONE -> control.complete();
                    case FAILED -> control.fail(redirect.failReason());
                    case RUNNING -> {}
                }
                return;
            }
            var client = control.client();
            var player = client.player;
            int radius = Math.max(4, control.step().getInt("radius", 16));
            net.minecraft.entity.ItemEntity best = null;
            double bestDist = (double) radius * radius;
            for (net.minecraft.entity.Entity e : client.world.getEntities()) {
                if (e instanceof net.minecraft.entity.ItemEntity item && !e.isRemoved()) {
                    double d = e.squaredDistanceTo(player);
                    if (d < bestDist) {
                        bestDist = d;
                        best = item;
                    }
                }
            }
            if (best == null) {
                if (++idleTicks > 20) { // 等 1 秒确认真的捡完了
                    BaritoneBridge.cancelAll();
                    control.complete();
                }
                return;
            }
            idleTicks = 0;
            if (repathCooldown-- <= 0) {
                BaritoneBridge.goNear(best.getBlockPos(), 1);
                repathCooldown = 15;
            }
        }
    }

    static class RememberHandler extends StepHandler {
        @Override
        public void start() {
            String name = control.step().getString("name", "").trim();
            if (name.isBlank()) {
                control.fail("remember 缺少 name 参数");
                return;
            }
            BlockPos pos = control.client().player.getBlockPos();
            String dim = control.client().world.getRegistryKey().getValue().getPath();
            ContextStore.remember(name, pos.getX(), pos.getY(), pos.getZ(), dim,
                    control.step().getString("note", ""));
            ChatUi.info("已记住地点「" + name + "」(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")");
            control.complete();
        }
    }

    static class ForgetHandler extends StepHandler {
        @Override
        public void start() {
            String name = control.step().getString("name", "").trim();
            if (ContextStore.forget(name)) {
                ChatUi.info("已删除地点「" + name + "」");
            } else {
                ChatUi.info("没有找到地点「" + name + "」");
            }
            control.complete();
        }
    }
}

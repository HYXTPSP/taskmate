package com.hyxt.taskmate.ai;

import com.hyxt.taskmate.exec.TaskExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把玩家当前的游戏状态序列化成文本,附在发给 AI 的消息里。 */
public final class ContextBuilder {

    private ContextBuilder() {}

    public static String snapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return "[当前状态] 玩家不在游戏世界中。";
        }
        StringBuilder sb = new StringBuilder("[当前状态]\n");
        BlockPos pos = player.getBlockPos();
        sb.append("- 坐标: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append('\n');
        sb.append("- 维度: ").append(client.world.getRegistryKey().getValue()).append('\n');
        sb.append("- 生命: ").append(String.format("%.0f", player.getHealth())).append("/20")
                .append(",饥饿: ").append(player.getHungerManager().getFoodLevel()).append("/20").append('\n');
        long time = client.world.getTimeOfDay() % 24000L;
        sb.append("- 时间: ").append(time).append(time >= 13000 && time <= 23000 ? " (夜晚)" : " (白天)").append('\n');
        sb.append("- Baritone: ").append(com.hyxt.taskmate.exec.BaritoneCheck.available()
                ? "可用" : "不可用(无法执行移动/挖掘类任务)").append('\n');
        sb.append("- 任务状态: ").append(TaskExecutor.INSTANCE.statusLine()).append('\n');
        sb.append("- 已记住的地点: ").append(ContextStore.describeMemories()).append('\n');

        // 背包(按物品聚合)
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            items.merge(id, stack.getCount(), Integer::sum);
        }
        if (items.isEmpty()) {
            sb.append("- 背包: 空\n");
        } else {
            sb.append("- 背包: ");
            int n = 0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                if (n++ > 0) sb.append(", ");
                if (n > 30) { sb.append("…"); break; }
                sb.append(e.getKey()).append("×").append(e.getValue());
            }
            sb.append('\n');
        }

        // 附近实体(16 格内,最多 10 个)
        sb.append("- 附近实体: ");
        int count = 0;
        for (Entity e : client.world.getEntities()) {
            if (e == player) continue;
            if (e.squaredDistanceTo(player) > 16 * 16) continue;
            if (count++ > 0) sb.append(", ");
            if (count > 10) { sb.append("…"); break; }
            sb.append(Registries.ENTITY_TYPE.getId(e.getType()).getPath())
                    .append("(").append((int) e.getX()).append(",").append((int) e.getY()).append(",").append((int) e.getZ()).append(")");
        }
        if (count == 0) sb.append("无");
        sb.append('\n');
        return sb.toString();
    }
}

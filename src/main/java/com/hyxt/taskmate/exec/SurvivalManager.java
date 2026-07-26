package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.ai.AiSession;
import com.hyxt.taskmate.ai.ContextStore;
import com.hyxt.taskmate.config.ModConfig;
import com.hyxt.taskmate.util.ChatUi;
import com.hyxt.taskmate.util.CombatHelper;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * 生存保障层(仅在任务执行期间生效):
 * - 自动进食:饥饿低于阈值时吃背包里的食物
 * - 遇袭反击:被打且敌对生物贴脸时,暂停任务反击,解除后自动继续
 * - 死亡处理:终止任务、记录死亡点、告知 AI
 */
public final class SurvivalManager {

    private static final Set<String> FOOD_BLACKLIST = Set.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chicken", "chorus_fruit");

    private static boolean eating = false;
    private static int prevSlot = -1;
    private static int eatingTicks = 0;

    private static Entity threat = null;
    private static boolean pausedForDefense = false;
    private static int defenseTicks = 0;

    private static boolean wasDead = false;

    private SurvivalManager() {}

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            stopEating(client);
            return;
        }
        ModConfig cfg = TaskmateClient.CONFIG;

        // ---- 死亡处理(任何状态下都生效)
        if (player.isDead() || player.getHealth() <= 0) {
            if (!wasDead) {
                wasDead = true;
                BlockPos pos = player.getBlockPos();
                String dim = client.world.getRegistryKey().getValue().getPath();
                ContextStore.remember("死亡点", pos.getX(), pos.getY(), pos.getZ(), dim, "上次死亡掉落位置");
                boolean hadTask = TaskExecutor.INSTANCE.isRunningOrPaused();
                TaskExecutor.INSTANCE.stopAll("玩家死亡");
                AiSession.INSTANCE.noteEvent("玩家死亡于 (" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + ", " + dim + "),已记为地点「死亡点」" + (hadTask ? ",当时的任务已终止" : ""));
                ChatUi.error("你死了…死亡位置已记为地点「死亡点」,复活后可以说 "
                        + cfg.triggerPrefix + "去死亡点捡装备");
            }
            stopEating(client);
            endDefense(false);
            return;
        }
        wasDead = false;

        TaskExecutor.State state = TaskExecutor.INSTANCE.state();
        boolean taskActive = state == TaskExecutor.State.RUNNING || pausedForDefense;
        if (!taskActive) {
            stopEating(client);
            endDefense(false);
            return;
        }

        // ---- 遇袭反击
        if (cfg.autoDefend) {
            tickDefense(client);
            if (pausedForDefense) return; // 战斗优先,不吃东西
        }

        // ---- 自动进食
        if (cfg.autoEat) {
            tickEating(client);
        }
    }

    // ------------------------------------------------ 反击

    private static void tickDefense(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!pausedForDefense) {
            // 触发条件:刚被打 + 敌对生物在 4.5 格内
            if (player.hurtTime > 0) {
                LivingEntity nearest = CombatHelper.findTarget(client, "hostile", 5);
                if (nearest != null) {
                    threat = nearest;
                    pausedForDefense = true;
                    defenseTicks = 0;
                    if (TaskExecutor.INSTANCE.state() == TaskExecutor.State.RUNNING) {
                        TaskExecutor.INSTANCE.pause("遭遇攻击,自动反击");
                    }
                    stopEating(client);
                    CombatHelper.equipBestWeapon(client);
                }
            }
            return;
        }
        // 反击进行中
        defenseTicks++;
        boolean cleared = CombatActions.fightTick(client, threat);
        if (cleared) {
            // 附近还有没有别的贴脸威胁?
            LivingEntity next = CombatHelper.findTarget(client, "hostile", 4);
            if (next != null && defenseTicks < 600) {
                threat = next;
                return;
            }
            endDefense(true);
        } else if (defenseTicks > 600) { // 30 秒还没打完,放弃反击交回控制
            endDefense(true);
        }
    }

    private static void endDefense(boolean resumeTask) {
        if (pausedForDefense) {
            pausedForDefense = false;
            threat = null;
            if (resumeTask && TaskExecutor.INSTANCE.state() == TaskExecutor.State.PAUSED) {
                ChatUi.info("威胁解除,继续任务。");
                TaskExecutor.INSTANCE.resume();
            }
        }
        threat = null;
        pausedForDefense = false;
    }

    // ------------------------------------------------ 进食

    private static void tickEating(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ModConfig cfg = TaskmateClient.CONFIG;
        if (!eating) {
            if (player.getHungerManager().getFoodLevel() > cfg.autoEatAt) return;
            int slot = InventoryHelper.findSlot(player, s ->
                    s.get(DataComponentTypes.FOOD) != null && !FOOD_BLACKLIST.contains(InventoryHelper.idOf(s)));
            if (slot < 0) return; // 没吃的,不打扰(AI 下次规划时会看到饥饿值)
            if (player.currentScreenHandler != player.playerScreenHandler) return; // 开着容器先不吃
            prevSlot = player.getInventory().getSelectedSlot();
            InventoryHelper.selectInHotbar(client, slot);
            eating = true;
            eatingTicks = 0;
            ChatUi.info("饿了,自动进食…");
        }
        // 按住右键吃
        eatingTicks++;
        if (player.getInventory().getSelectedStack().get(DataComponentTypes.FOOD) == null
                || player.getHungerManager().getFoodLevel() >= 20
                || eatingTicks > 100) {
            stopEating(client);
            return;
        }
        client.options.useKey.setPressed(true);
    }

    private static void stopEating(MinecraftClient client) {
        if (eating) {
            client.options.useKey.setPressed(false);
            if (prevSlot >= 0 && client.player != null) {
                client.player.getInventory().setSelectedSlot(prevSlot);
            }
            eating = false;
            prevSlot = -1;
        }
    }
}

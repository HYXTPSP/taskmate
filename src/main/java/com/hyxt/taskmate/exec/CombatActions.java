package com.hyxt.taskmate.exec;

import com.hyxt.taskmate.TaskmateClient;
import com.hyxt.taskmate.api.ActionDefinition;
import com.hyxt.taskmate.api.StepHandler;
import com.hyxt.taskmate.util.CombatHelper;
import com.hyxt.taskmate.util.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

import java.util.List;

/** kill:索敌、接近、攻击。 */
final class CombatActions {

    private CombatActions() {}

    static void register() {
        ActionRegistry.register(new ActionDefinition(
                "kill", List.of("attack", "fight"),
                "{\"action\":\"kill\",\"target\":\"zombie\",\"count\":3,\"radius\":16,\"desc\":\"...\"} —— 击杀附近的目标;target 可以是实体注册名(zombie/skeleton/cow…)或 \"hostile\"(任意敌对生物);count 默认 1,radius 默认 16",
                true, KillHandler::new));
    }

    static class KillHandler extends StepHandler {
        private String target;
        private int wantKills;
        private int radius;
        private int kills;
        private LivingEntity current;
        private int repathCooldown;

        @Override
        public void start() {
            target = InventoryHelper.normalizeId(control.step().getString("target", "hostile"));
            wantKills = Math.max(1, control.step().getInt("count", 1));
            radius = Math.max(4, control.step().getInt("radius", 16));
            CombatHelper.equipBestWeapon(control.client());
        }

        @Override
        public void tick() {
            MinecraftClient client = control.client();
            ClientPlayerEntity player = client.player;

            // 血量保护
            if (player.getHealth() <= TaskmateClient.CONFIG.combatAbortHealth) {
                BaritoneBridge.cancelAll();
                control.fail("生命值过低(" + String.format("%.0f", player.getHealth()) + "),中止战斗。建议先吃东西或撤退");
                return;
            }

            // 当前目标死了/没了 → 计数
            if (current != null && (current.isRemoved() || current.isDead())) {
                kills++;
                current = null;
                BaritoneBridge.cancelAll();
            }
            if (kills >= wantKills) {
                control.complete();
                return;
            }

            // 选目标
            if (current == null) {
                current = CombatHelper.findTarget(client, target, radius);
                if (current == null) {
                    if (kills > 0) {
                        control.complete(); // 杀了一部分,附近没有了
                    } else {
                        control.fail("附近 " + radius + " 格内没有目标: " + target);
                    }
                    return;
                }
                repathCooldown = 0;
            }

            double dist = player.distanceTo(current);
            if (dist > 3.2) {
                if (repathCooldown-- <= 0) {
                    BaritoneBridge.goNear(current.getBlockPos(), 1);
                    repathCooldown = 10;
                }
                return;
            }
            BaritoneBridge.cancelAll();
            CombatHelper.attackIfReady(client, current);
        }

        @Override
        public void onCancel() {
            current = null;
        }
    }

    /** 供生存保障层复用的简单近战逻辑 */
    public static boolean fightTick(MinecraftClient client, Entity threat) {
        ClientPlayerEntity player = client.player;
        if (player == null || threat == null || threat.isRemoved()
                || (threat instanceof LivingEntity le && le.isDead())) {
            return true; // 威胁解除
        }
        double dist = player.distanceTo(threat);
        if (dist > 4.0) {
            return true; // 脱离范围,不追击
        }
        CombatHelper.attackIfReady(client, threat);
        return false;
    }
}

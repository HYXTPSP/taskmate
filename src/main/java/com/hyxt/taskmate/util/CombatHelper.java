package com.hyxt.taskmate.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;

/** 战斗小工具:选武器、索敌、攻击节奏。 */
public final class CombatHelper {

    private CombatHelper() {}

    /** 把背包里"最像武器"的物品换到手上:剑 > 斧 > 三叉戟 > 现状 */
    public static void equipBestWeapon(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        int slot = InventoryHelper.findSlot(player, s -> InventoryHelper.idOf(s).endsWith("_sword"));
        if (slot < 0) slot = InventoryHelper.findSlot(player, s -> InventoryHelper.idOf(s).endsWith("_axe"));
        if (slot < 0) slot = InventoryHelper.findSlot(player, s -> InventoryHelper.idOf(s).equals("trident"));
        if (slot >= 0) InventoryHelper.selectInHotbar(client, slot);
    }

    /** 找最近的匹配目标。targetName 为实体注册名或 "hostile" */
    public static LivingEntity findTarget(MinecraftClient client, String targetName, int radius) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return null;
        LivingEntity best = null;
        double bestDist = (double) radius * radius;
        for (Entity e : client.world.getEntities()) {
            if (e == player || !(e instanceof LivingEntity le) || le.isDead()) continue;
            boolean match;
            if ("hostile".equals(targetName) || "monster".equals(targetName)) {
                match = e instanceof HostileEntity;
            } else {
                match = Registries.ENTITY_TYPE.getId(e.getType()).getPath().equals(targetName);
            }
            if (!match) continue;
            double d = e.squaredDistanceTo(player);
            if (d < bestDist) {
                bestDist = d;
                best = le;
            }
        }
        return best;
    }

    /** 面向目标,攻击冷却满时挥刀 */
    public static void attackIfReady(MinecraftClient client, Entity target) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        if (player.getAttackCooldownProgress(0.0f) >= 1.0f) {
            client.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
        }
    }
}

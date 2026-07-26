package com.hyxt.taskmate.util;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/** 方块查找与交互工具。 */
public final class BlockInteraction {

    public static final double REACH = 4.2;

    private BlockInteraction() {}

    /** 在玩家周围 radius 内找最近的指定方块,找不到返回 null */
    public static BlockPos findNearest(MinecraftClient client, Set<Block> blocks, int radius) {
        if (client.player == null || client.world == null) return null;
        BlockPos center = client.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            if (blocks.contains(client.world.getBlockState(pos).getBlock())) {
                double d = pos.getSquaredDistance(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.toImmutable();
                }
            }
        }
        return best;
    }

    public static double distanceTo(MinecraftClient client, BlockPos pos) {
        if (client.player == null) return Double.MAX_VALUE;
        return Math.sqrt(client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)));
    }

    public static boolean inReach(MinecraftClient client, BlockPos pos) {
        return distanceTo(client, pos) <= REACH;
    }

    public static void lookAt(MinecraftClient client, BlockPos pos) {
        if (client.player == null) return;
        client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(pos));
    }

    /** 右键指定方块(用于打开容器/工作台等),需在触及范围内 */
    public static void interact(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.interactionManager == null) return;
        lookAt(client, pos);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
    }
}

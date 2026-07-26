package com.hyxt.taskmate.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 通用放置算法:在玩家周围 3 格内找任意可放置位(有实心相邻面即可,支持贴墙放),
 * 避开玩家碰撞箱和右键会弹界面的方块。
 */
public final class BlockPlacer {

    public record Spot(BlockPos pos, BlockPos support, Direction face) {}

    private BlockPlacer() {}

    /** 右键这个 support 会打开界面/被交互,不能作为放置依托 */
    private static boolean isInteractive(MinecraftClient client, BlockPos support) {
        String id = net.minecraft.registry.Registries.BLOCK
                .getId(client.world.getBlockState(support).getBlock()).getPath();
        return id.contains("crafting_table") || id.contains("furnace") || id.contains("smoker")
                || id.contains("chest") || id.contains("barrel") || id.contains("shulker")
                || id.contains("anvil") || id.contains("door") || id.contains("gate")
                || id.contains("button") || id.contains("lever") || id.endsWith("_bed")
                || id.contains("stonecutter") || id.contains("grindstone") || id.contains("loom")
                || id.contains("smithing") || id.contains("cartography") || id.contains("enchanting")
                || id.contains("brewing") || id.contains("hopper") || id.contains("dispenser")
                || id.contains("dropper") || id.contains("repeater") || id.contains("comparator")
                || id.contains("note_block") || id.contains("jukebox") || id.contains("bell");
    }

    /** 找一个可放置位。优先"放在实心地面上",其次贴墙/贴顶。找不到返回 null */
    public static Spot findSpot(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return null;
        BlockPos base = player.getBlockPos();
        Box playerBox = player.getBoundingBox();
        Spot fallback = null;

        for (BlockPos p : BlockPos.iterate(base.add(-3, -2, -3), base.add(3, 2, 3))) {
            if (!client.world.getBlockState(p).isReplaceable()) continue;
            BlockPos pos = p.toImmutable();
            if (playerBox.intersects(new Box(pos))) continue; // 别放进自己身体里
            if (BlockInteraction.distanceTo(client, pos) > BlockInteraction.REACH) continue;

            // 优先地面
            BlockPos below = pos.down();
            if (client.world.getBlockState(below).isSolidBlock(client.world, below)
                    && !isInteractive(client, below)) {
                return new Spot(pos, below, Direction.UP);
            }
            // 其次任意实心相邻面(贴墙/贴顶)
            if (fallback == null) {
                for (Direction d : Direction.values()) {
                    if (d == Direction.DOWN) continue; // 上面已试过
                    BlockPos support = pos.offset(d);
                    if (client.world.getBlockState(support).isSolidBlock(client.world, support)
                            && !isInteractive(client, support)
                            && BlockInteraction.distanceTo(client, support) <= BlockInteraction.REACH) {
                        fallback = new Spot(pos, support, d.getOpposite());
                        break;
                    }
                }
            }
        }
        return fallback;
    }

    /** 对准并放置,返回预期的放置位置(未必成功,调用方下 tick 校验) */
    public static BlockPos place(MinecraftClient client, Spot spot) {
        ClientPlayerEntity player = client.player;
        if (player == null) return null;
        Vec3d hitVec = Vec3d.ofCenter(spot.support())
                .add(Vec3d.of(spot.face().getVector()).multiply(0.5));
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, spot.face(), spot.support(), false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        return spot.pos();
    }
}

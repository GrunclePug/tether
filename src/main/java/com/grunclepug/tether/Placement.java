package com.grunclepug.tether;

import com.grunclepug.tether.mixin.BlockItemInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * Plans and performs a block placement that yields a wanted block state. The planner tries every face it could click,
 * a few hit heights and the four facings, asks the held item which state each would produce (the same code the server
 * runs), and keeps the matching option that needs the least head movement. Placement always sneaks, so clicking a
 * chest or door places against it instead of opening it.
 */
final class Placement {
    private static final float[] YAWS = {0f, 90f, 180f, -90f};
    private static final float[] PITCHES = {0f, 70f, -70f};
    /** Hit points on a side face: centre, upper and lower half (stairs, slabs, trapdoors). */
    private static final Vec3[] SIDE_OFFSETS = {Vec3.ZERO, new Vec3(0, 0.3, 0), new Vec3(0, -0.3, 0)};
    /** Hit points on a top or bottom face: centre and towards each edge (door hinges). */
    private static final Vec3[] TOP_OFFSETS = {Vec3.ZERO, new Vec3(0.3, 0, 0), new Vec3(-0.3, 0, 0),
            new Vec3(0, 0, 0.3), new Vec3(0, 0, -0.3)};

    record Plan(BlockPos against, Direction face, Vec3 hit, float yaw, float pitch, BlockState result) {
    }

    private Placement() {
    }

    /**
     * Properties that placement doesn't choose: they follow from neighbours (connections, stair shape), the world
     * (water, snow, power, leaf distance), or a later click (open). Matching ignores them, so a state copied from
     * {@code region} still matches what placing it produces; {@code open} is handled by clicking after placement.
     */
    private static final Set<Property<?>> DERIVED = Set.of(
            BlockStateProperties.WATERLOGGED, BlockStateProperties.SNOWY,
            BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH,
            BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN,
            BlockStateProperties.NORTH_WALL, BlockStateProperties.EAST_WALL, BlockStateProperties.SOUTH_WALL,
            BlockStateProperties.WEST_WALL, BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.DISTANCE,
            BlockStateProperties.STABILITY_DISTANCE, BlockStateProperties.PERSISTENT, BlockStateProperties.POWERED,
            BlockStateProperties.IN_WALL, BlockStateProperties.OCCUPIED, BlockStateProperties.OPEN,
            BlockStateProperties.LIT, BlockStateProperties.HAS_BOOK, BlockStateProperties.TRIGGERED,
            BlockStateProperties.ENABLED, BlockStateProperties.ATTACHED, BlockStateProperties.DISARMED,
            BlockStateProperties.SIGNAL_FIRE, BlockStateProperties.BOTTOM, BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
            BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE);

    /**
     * Whether a state satisfies a want: same block, and equal values for every property the caller named, apart from
     * the {@link #DERIVED} ones.
     */
    static boolean matches(BlockState state, BlockState want, Map<Property<?>, Comparable<?>> props) {
        if (want == null) {
            return state.isAir();
        }
        if (state.getBlock() != want.getBlock()) {
            return false;
        }
        for (Property<?> prop : props.keySet()) {
            if (DERIVED.contains(prop)) {
                continue;
            }
            if (!state.getValue(prop).equals(want.getValue(prop))) {
                return false;
            }
        }
        return true;
    }

    /** Every distinct state the held item could be placed as at pos from where the player stands. */
    static java.util.Set<String> options(Minecraft mc, LocalPlayer p, BlockPos pos) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        ItemStack stack = p.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return out;
        }
        BlockState any = item.getBlock().defaultBlockState();
        plan(mc, p, pos, any, Map.of(), c -> out.add(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(c.result())));
        return out;
    }

    /** Finds how to place the held item at pos so the result matches; null if no reachable option does. */
    static Plan plan(Minecraft mc, LocalPlayer p, BlockPos pos, BlockState want, Map<Property<?>, Comparable<?>> props) {
        Plan[] best = {null};
        double[] bestScore = {Double.MAX_VALUE};
        float yaw0 = p.getYRot();
        float pitch0 = p.getXRot();
        plan(mc, p, pos, want, props, c -> {
            double score = turnCost(p, c, yaw0, pitch0);
            if (score < bestScore[0]) {
                best[0] = c;
                bestScore[0] = score;
            }
        });
        return best[0];
    }

    private static void plan(Minecraft mc, LocalPlayer p, BlockPos pos, BlockState want,
                             Map<Property<?>, Comparable<?>> props, java.util.function.Consumer<Plan> sink) {
        ItemStack stack = p.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return;
        }
        ClientLevel level = mc.level;
        float yaw0 = p.getYRot();
        float pitch0 = p.getXRot();
        boolean shift0 = p.isShiftKeyDown();
        boolean found = false;
        p.setShiftKeyDown(true);
        try {
            for (Direction side : Direction.values()) {
                BlockPos against = pos.relative(side);
                Direction face = side.getOpposite();
                BlockState as = level.getBlockState(against);
                if (as.getShape(level, against).isEmpty() || !p.canInteractWithBlock(against, 1.0)) {
                    continue;
                }
                for (Vec3 offset : face.getAxis().isHorizontal() ? SIDE_OFFSETS : TOP_OFFSETS) {
                    Vec3 hit = Vec3.atCenterOf(against).relative(face, 0.5).add(offset);
                    for (Plan c : candidates(p, item, stack, against, face, hit, pos, want, props)) {
                        sink.accept(c);
                        found = true;
                    }
                }
            }
            // Clicking the target itself, for states that merge into an existing block (slab to double slab).
            BlockState here = level.getBlockState(pos);
            if (!found && !here.isAir() && here.getBlock() == want.getBlock() && p.canInteractWithBlock(pos, 1.0)) {
                for (Direction face : Direction.values()) {
                    Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
                    for (Plan c : candidates(p, item, stack, pos, face, hit, pos, want, props)) {
                        sink.accept(c);
                    }
                }
            }
        } finally {
            p.setYRot(yaw0);
            p.setXRot(pitch0);
            p.setShiftKeyDown(shift0);
        }
    }

    private static java.util.List<Plan> candidates(LocalPlayer p, BlockItem item, ItemStack stack, BlockPos against,
                                                   Direction face, Vec3 hit, BlockPos pos, BlockState want,
                                                   Map<Property<?>, Comparable<?>> props) {
        java.util.List<Plan> out = new java.util.ArrayList<>();
        BlockItemInvoker inv = (BlockItemInvoker) item;
        // The natural rotation (looking straight at the hit point) first, then the cardinal facings.
        Vec3 d = hit.subtract(p.getEyePosition());
        float natYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float natPitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        java.util.List<float[]> rotations = new java.util.ArrayList<>();
        rotations.add(new float[]{natYaw, natPitch});
        for (float y : YAWS) {
            for (float x : PITCHES) {
                rotations.add(new float[]{y, x});
            }
        }
        for (float[] r : rotations) {
            p.setYRot(r[0]);
            p.setXRot(r[1]);
            BlockPlaceContext ctx = item.updatePlacementContext(
                    new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, new BlockHitResult(hit, face, against, false)));
            if (ctx == null || !ctx.getClickedPos().equals(pos) || !ctx.canPlace()) {
                continue;
            }
            BlockState result = inv.tether$getPlacementState(ctx);
            if (result == null || !inv.tether$canPlace(ctx, result) || !matches(result, want, props)) {
                continue;
            }
            out.add(new Plan(against, face, hit, r[0], r[1], result));
        }
        return out;
    }

    private static double turnCost(LocalPlayer p, Plan c, float yaw0, float pitch0) {
        Vec3 d = c.hit().subtract(p.getEyePosition());
        float natYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        return Math.abs(Mth.wrapDegrees(c.yaw() - natYaw)) + Math.abs(c.pitch() - pitch0) * 0.5;
    }

    /** Clicks a block without sneaking, to open or shut a door, trapdoor or gate. */
    static void toggle(Minecraft mc, LocalPlayer p, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.getNearest(p.getEyePosition().subtract(center));
        Vec3 hit = center.relative(face, 0.5);
        Actions.lookAt(p, hit);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        if (r.shouldSwing()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Turns to the plan's rotation, tells the server, and clicks while sneaking. */
    static InteractionResult execute(Minecraft mc, LocalPlayer p, Plan plan) {
        p.setYRot(plan.yaw());
        p.setXRot(plan.pitch());
        p.setYHeadRot(plan.yaw());
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(plan.yaw(), plan.pitch(), p.onGround()));
        boolean wasSneaking = p.isShiftKeyDown();
        if (!wasSneaking) {
            p.setShiftKeyDown(true);
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        }
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(plan.hit(), plan.face(), plan.against(), false));
        if (!wasSneaking) {
            p.setShiftKeyDown(false);
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
        if (r.shouldSwing()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        return r;
    }
}

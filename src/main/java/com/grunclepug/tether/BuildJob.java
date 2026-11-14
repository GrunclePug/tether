package com.grunclepug.tether;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.grunclepug.tether.compat.BaritoneCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a list of positions into wanted block states, a step at a time on the client tick: blocks that already match
 * are skipped, wrong blocks are mined (with the best hotbar tool), and the right item is put in hand and placed with
 * {@link Placement}; doors, trapdoors and gates are then clicked open or shut if asked. A step with nothing to place
 * against is retried once after the rest. With walking enabled, Baritone moves the player into reach first, and with
 * pickup it collects the drops afterwards. Ten ticks after the last step every touched position is re-read, so
 * server-side rejections show up as mismatches.
 */
final class BuildJob {
    /** want == null means the position should be air. */
    record Step(BlockPos pos, BlockState want, Map<Property<?>, Comparable<?>> props, String spec) {
    }

    private enum Phase { NEXT, BREAKING, WALKING, TOGGLE, COLLECT, VERIFY, DONE }

    private static final int BREAK_TIMEOUT = 20 * 30;
    private static final int WALK_TIMEOUT = 20 * 45;
    private static final int PICKUP_TIMEOUT = 20 * 10;
    private static final int MAX_BREAKS_PER_STEP = 2;

    private final List<Step> steps;
    private final String[] results;
    private final boolean[] deferred;
    private final Deque<Integer> queue = new ArrayDeque<>();
    private final boolean walk;
    private final boolean pickup;
    private final int perTick;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private final List<BlockPos> brokeAt = new ArrayList<>();

    private Phase phase = Phase.NEXT;
    private int current = -1;
    private int timer;
    private int breaks;
    private boolean walked;
    private Direction breakFace;
    private int picked;
    private int toggleIndex;
    /** Ticks to wait after giving Baritone a goal before trusting "not busy": it starts pathing a tick or two later. */
    private int grace;
    private final java.util.Set<Integer> visited = new java.util.HashSet<>();

    BuildJob(List<Step> steps, boolean walk, boolean pickup, int perTick) {
        this.steps = steps;
        this.results = new String[steps.size()];
        this.deferred = new boolean[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            queue.add(i);
        }
        this.walk = walk;
        this.pickup = pickup;
        this.perTick = perTick;
    }

    CompletableFuture<JsonObject> future() {
        return done;
    }

    boolean breaking() {
        return phase == Phase.BREAKING;
    }

    boolean finished() {
        return phase == Phase.DONE;
    }

    /** Parses "minecraft:oak_stairs[facing=north,half=top]" (or "air") into a step. */
    static Step step(BlockPos pos, String spec) {
        if (spec.equals("air") || spec.equals("minecraft:air")) {
            return new Step(pos, null, Map.of(), "minecraft:air");
        }
        try {
            BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), spec, false);
            return new Step(pos, r.blockState(), r.properties(), spec);
        } catch (Exception e) {
            throw new ApiException("bad block '" + spec + "': " + e.getMessage());
        }
    }

    JsonObject progress() {
        JsonObject o = new JsonObject();
        o.addProperty("phase", phase.name().toLowerCase());
        o.addProperty("remaining", queue.size());
        o.addProperty("total", steps.size());
        return o;
    }

    void cancel(Minecraft mc) {
        if (phase == Phase.BREAKING && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        if ((phase == Phase.WALKING || phase == Phase.COLLECT) && Actions.baritone) {
            BaritoneCompat.cancel();
        }
        finish(mc.level, "stopped");
    }

    void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        switch (phase) {
            case NEXT -> {
                for (int n = 0; n < perTick && phase == Phase.NEXT; n++) {
                    next(mc, p);
                }
            }
            case BREAKING -> tickBreak(mc, p);
            case WALKING -> {
                if (grace > 0) {
                    grace--;
                    return;
                }
                if (!BaritoneCompat.busy() || --timer <= 0) {
                    if (timer <= 0) {
                        BaritoneCompat.cancel();
                    }
                    phase = Phase.NEXT;
                }
            }
            case TOGGLE -> {
                // Wait for the server to confirm the placement before clicking it open or shut.
                if (--timer <= 0) {
                    Step s = steps.get(toggleIndex);
                    BlockState here = mc.level.getBlockState(s.pos());
                    if (openDiffers(here, s)) {
                        Placement.toggle(mc, p, s.pos());
                    }
                    phase = Phase.NEXT;
                }
            }
            case COLLECT -> tickCollect(mc, p);
            case VERIFY -> {
                if (--timer <= 0) {
                    finish(mc.level, "done");
                }
            }
            case DONE -> {
            }
        }
    }

    private void complete(String result) {
        results[current] = result;
        current = -1;
        breaks = 0;
        walked = false;
    }

    private void next(Minecraft mc, LocalPlayer p) {
        if (current < 0) {
            if (queue.isEmpty()) {
                if (pickup && Actions.baritone) {
                    phase = Phase.COLLECT;
                    timer = 0;
                } else {
                    phase = Phase.VERIFY;
                    timer = 10;
                }
                return;
            }
            current = queue.poll();
        }
        if (p.containerMenu != p.inventoryMenu || mc.screen != null) {
            p.closeContainer();
            mc.setScreen(null);
        }
        ClientLevel level = mc.level;
        Step s = steps.get(current);
        BlockState here = level.getBlockState(s.pos());
        boolean secondHalf = s.want() != null && isSecondHalf(s.want());
        if (secondHalf && !Placement.matches(here, s.want(), s.props()) && (here.isAir() || here.canBeReplaced())) {
            // The upper half of a door or the head of a bed comes with its other half; the end check verifies it.
            complete("placed");
            return;
        }
        if (Placement.matches(here, s.want(), s.props())) {
            if (s.want() != null && openDiffers(here, s)) {
                if (!p.canInteractWithBlock(s.pos(), 1.0)) {
                    complete("failed: out of reach to open/close");
                    return;
                }
                Placement.toggle(mc, p, s.pos());
                complete("placed");
                return;
            }
            complete(results[current] == null ? "already" : results[current]);
            return;
        }
        if (!p.canInteractWithBlock(s.pos(), 1.0)) {
            if (walk && !walked && Actions.baritone) {
                BaritoneCompat.goNear(s.pos(), 2);
                grace = 10;
                walked = true;
                phase = Phase.WALKING;
                timer = WALK_TIMEOUT;
            } else {
                complete("failed: out of reach");
            }
            return;
        }
        if (s.want() != null && !secondHalf) {
            Item item = s.want().getBlock().asItem();
            if (item == Items.AIR) {
                complete("failed: no item places " + s.spec());
                return;
            }
            if (!Hotbar.hold(mc, p, item)) {
                complete("failed: no " + BuiltInRegistries.ITEM.getKey(item) + " in inventory");
                return;
            }
            Placement.Plan plan = Placement.plan(mc, p, s.pos(), s.want(), s.props());
            if (plan != null) {
                InteractionResult r = Placement.execute(mc, p, plan);
                if (!r.consumesAction()) {
                    complete("failed: placement rejected (" + r.name() + ")");
                    return;
                }
                results[current] = "placed";
                if (s.props().containsKey(BlockStateProperties.OPEN)) {
                    phase = Phase.TOGGLE;
                    timer = 3;
                    toggleIndex = current;
                    // Re-run this step after the toggle so it ends up verified and completed.
                    queue.addFirst(current);
                    current = -1;
                    breaks = 0;
                    return;
                }
                complete("placed");
                return;
            }
            if (here.isAir() || here.canBeReplaced()) {
                boolean inTheWay = p.getBoundingBox().intersects(new AABB(s.pos()));
                if (!deferred[current]) {
                    // Maybe a later step builds the neighbour it needs, or the player moves: try again at the end.
                    deferred[current] = true;
                    queue.addLast(current);
                    current = -1;
                    return;
                }
                if (inTheWay) {
                    complete("failed: player in the way");
                } else {
                    java.util.Set<String> reachable = Placement.options(mc, p, s.pos());
                    complete("failed: no reachable face gives that state (it depends on which neighbour is clicked)"
                            + (reachable.isEmpty() ? "; nothing solid to place against"
                            : "; reachable from here: " + String.join(" | ", reachable)));
                }
                return;
            }
        }
        // Something's in the way of the wanted state: mine it, then retry this step.
        if (here.getDestroySpeed(level, s.pos()) < 0) {
            complete("failed: unbreakable " + BlockStateParser.serialize(here));
            return;
        }
        if (breaks++ >= MAX_BREAKS_PER_STEP) {
            complete("failed: block keeps coming back");
            return;
        }
        Hotbar.bestTool(p, here);
        Vec3 center = Vec3.atCenterOf(s.pos());
        breakFace = Direction.getNearest(p.getEyePosition().subtract(center));
        Actions.lookAt(p, center.relative(breakFace, 0.5));
        mc.gameMode.startDestroyBlock(s.pos(), breakFace);
        p.swing(InteractionHand.MAIN_HAND);
        phase = Phase.BREAKING;
        timer = BREAK_TIMEOUT;
    }

    private void tickBreak(Minecraft mc, LocalPlayer p) {
        Step s = steps.get(current);
        if (mc.level.getBlockState(s.pos()).isAir()) {
            brokeAt.add(s.pos());
            phase = Phase.NEXT;
            if (s.want() == null) {
                complete("broken");
            }
            return;
        }
        if (--timer <= 0) {
            mc.gameMode.stopDestroyBlock();
            phase = Phase.NEXT;
            complete("failed: breaking timed out");
            return;
        }
        mc.gameMode.continueDestroyBlock(s.pos(), breakFace);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Walks onto each dropped item near where blocks were mined, nearest first. */
    private void tickCollect(Minecraft mc, LocalPlayer p) {
        if (grace > 0) {
            grace--;
            return;
        }
        if (timer > 0) {
            if (BaritoneCompat.busy() && --timer > 0) {
                return;
            }
            if (BaritoneCompat.busy()) {
                BaritoneCompat.cancel();
            }
            timer = 0;
        }
        ItemEntity nearest = null;
        if (!brokeAt.isEmpty()) {
            nearest = mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12),
                            e -> !visited.contains(e.getId())
                                    && brokeAt.stream().anyMatch(b -> b.closerToCenterThan(e.position(), 4)))
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
        }
        if (nearest == null || picked >= 64) {
            phase = Phase.VERIFY;
            timer = 10;
            return;
        }
        picked++;
        visited.add(nearest.getId());
        BaritoneCompat.goTo(nearest.blockPosition());
        grace = 10;
        timer = PICKUP_TIMEOUT;
    }

    private static boolean isSecondHalf(BlockState want) {
        return (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (want.hasProperty(BlockStateProperties.BED_PART)
                && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    private static boolean openDiffers(BlockState here, Step s) {
        Comparable<?> want = s.props().get(BlockStateProperties.OPEN);
        return want != null && here.hasProperty(BlockStateProperties.OPEN)
                && !here.getValue(BlockStateProperties.OPEN).equals(want);
    }

    private void finish(ClientLevel level, String outcome) {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        JsonObject o = new JsonObject();
        o.addProperty("outcome", outcome);
        o.addProperty("total", steps.size());
        int placed = 0;
        int broken = 0;
        int already = 0;
        JsonArray problems = new JsonArray();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String r = results[i];
            if (r == null) {
                r = "not reached";
            } else if (level != null && (r.equals("placed") || r.equals("broken"))) {
                BlockState actual = level.getBlockState(s.pos());
                if (!Placement.matches(actual, s.want(), s.props()) || openDiffers(actual, s)) {
                    r = "mismatch: got " + BlockStateParser.serialize(actual);
                }
            }
            switch (r) {
                case "placed" -> placed++;
                case "broken" -> broken++;
                case "already" -> already++;
                default -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("i", i);
                    e.addProperty("x", s.pos().getX());
                    e.addProperty("y", s.pos().getY());
                    e.addProperty("z", s.pos().getZ());
                    e.addProperty("want", s.spec());
                    e.addProperty("problem", r);
                    problems.add(e);
                }
            }
        }
        o.addProperty("placed", placed);
        o.addProperty("broken", broken);
        o.addProperty("already", already);
        if (pickup) {
            o.addProperty("pickupTrips", picked);
        }
        o.add("problems", problems);
        done.complete(o);
    }
}

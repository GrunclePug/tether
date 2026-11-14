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

    private enum Phase { NEXT, BREAKING, WALKING, NUDGE, TOGGLE, COLLECT, VERIFY, DONE }

    private static final int BREAK_TIMEOUT = 20 * 30;
    private static final int WALK_TIMEOUT = 20 * 45;
    private static final int PICKUP_TIMEOUT = 20 * 10;
    /** Baritone says it's moving but the player hasn't moved this far in STUCK_TICKS: stuck. */
    private static final double STUCK_DISTANCE = 0.3;
    private static final int STUCK_TICKS = 60;
    /** Recoveries per job before giving up and asking for help. */
    private static final int MAX_RECOVERIES = 2;
    /** Walks in a row that end out of reach before the job stops. */
    private static final int MAX_WALK_FAILS = 3;
    private static final int NUDGE_TICKS = 12;
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
    /** The movement key the stuck-recovery nudge holds: one that leads somewhere safe to stand. */
    private net.minecraft.client.KeyMapping nudgeKey;
    /** The dropped item the current pickup walk is heading for. */
    private int collectTarget = -1;
    /** Ticks to wait after giving Baritone a goal before trusting "not busy": it starts pathing a tick or two later. */
    private int grace;
    private Vec3 lastPos;
    private int stillTicks;
    private int recoveries;
    private int walkFails;
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

    /**
     * Stops acting but keeps the place in the list: a half-mined block is let go and re-mined on resume, a walk is
     * cancelled and re-walked, a back-up nudge is dropped. Ticking stops while paused (see Actions).
     */
    void pause(Minecraft mc) {
        switch (phase) {
            case BREAKING -> {
                mc.gameMode.stopDestroyBlock();
                breaks = Math.max(0, breaks - 1);
                phase = Phase.NEXT;
            }
            case WALKING -> {
                BaritoneCompat.cancel();
                walked = false;
                phase = Phase.NEXT;
            }
            case NUDGE -> {
                releaseKeys(mc);
                phase = Phase.NEXT;
            }
            case COLLECT -> {
                BaritoneCompat.cancel();
                timer = 0;
                // Go back for the same item after the pause.
                visited.remove(collectTarget);
            }
            default -> {
            }
        }
        lastPos = null;
        stillTicks = 0;
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
        o.addProperty("recoveries", recoveries);
        return o;
    }

    void cancel(Minecraft mc) {
        if (phase == Phase.BREAKING && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        if ((phase == Phase.WALKING || phase == Phase.COLLECT) && Actions.baritone) {
            BaritoneCompat.cancel();
        }
        if (phase == Phase.NUDGE) {
            releaseKeys(mc);
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
                if (!BaritoneCompat.busy()) {
                    // Arrived, or Baritone gave up; next() checks the reach again.
                    phase = Phase.NEXT;
                } else if (--timer <= 0 || stuck(p)) {
                    recover(mc, p);
                }
            }
            case NUDGE -> {
                if (--timer > 0) {
                    nudgeKey.setDown(true);
                    mc.options.keyJump.setDown(true);
                } else {
                    releaseKeys(mc);
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
                if (!Placement.toggle(mc, p, s.pos())) {
                    complete("failed: " + BuiltInRegistries.BLOCK.getKey(here.getBlock())
                            + " can't be opened or shut by hand");
                    return;
                }
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
                lastPos = null;
                stillTicks = 0;
            } else {
                complete("failed: out of reach");
                if (walk && ++walkFails >= MAX_WALK_FAILS) {
                    giveUp(mc, p, "couldn't get within reach of " + MAX_WALK_FAILS + " blocks in a row");
                }
            }
            return;
        }
        walkFails = 0;
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
        if (!Hotbar.bestTool(p, here)) {
            complete("failed: no tool in the hotbar can harvest " + BuiltInRegistries.BLOCK.getKey(here.getBlock())
                    + " (mining it would destroy it with no drop)");
            return;
        }
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
            if (BaritoneCompat.busy() && stuck(p)) {
                // Not worth a fight for a dropped item: skip it, and steer clear of whatever caught us.
                BaritoneCompat.cancel();
                avoidCulprit(mc, p);
                timer = 0;
                return;
            }
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
        collectTarget = nearest.getId();
        BaritoneCompat.goTo(nearest.blockPosition());
        grace = 10;
        timer = PICKUP_TIMEOUT;
        lastPos = null;
        stillTicks = 0;
    }

    /** True once the player has barely moved for STUCK_TICKS while Baritone thinks it's walking. */
    private boolean stuck(LocalPlayer p) {
        Vec3 pos = p.position();
        if (lastPos == null || pos.distanceToSqr(lastPos) > STUCK_DISTANCE * STUCK_DISTANCE) {
            lastPos = pos;
            stillTicks = 0;
            return false;
        }
        return ++stillTicks >= STUCK_TICKS;
    }

    /**
     * Gets out of a stuck walk: cancels the path, tells Baritone to avoid the mod block that probably caught the
     * player, backs up with a jump, and lets the step walk again. After MAX_RECOVERIES the job stops and says so.
     */
    private void recover(Minecraft mc, LocalPlayer p) {
        BaritoneCompat.cancel();
        String culprit = avoidCulprit(mc, p);
        String where = p.blockPosition().toShortString();
        if (++recoveries > MAX_RECOVERIES) {
            giveUp(mc, p, "stuck at " + where + (culprit == null ? "" : " near " + culprit));
            return;
        }
        nudgeKey = safeNudge(mc, p);
        if (nudgeKey == null) {
            giveUp(mc, p, "stuck at " + where + " with no safe direction to back away");
            return;
        }
        Overlay.setStatus("Stuck at " + where + (culprit == null ? "" : " on " + culprit)
                + ". Backing up and trying another way.");
        walked = false;
        phase = Phase.NUDGE;
        timer = NUDGE_TICKS;
    }

    /**
     * Picks a direction to step away in (back, right, left, then forward) where the player lands on something solid:
     * either a floor one step over, or a block to hop onto with headroom. Never toward a drop or lava. Null if none.
     */
    private static net.minecraft.client.KeyMapping safeNudge(Minecraft mc, LocalPlayer p) {
        Direction facing = p.getDirection();
        Direction[] dirs = {facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise(), facing};
        net.minecraft.client.KeyMapping[] keys = {mc.options.keyDown, mc.options.keyRight, mc.options.keyLeft,
                mc.options.keyUp};
        BlockPos feet = p.blockPosition();
        for (int i = 0; i < dirs.length; i++) {
            BlockPos to = feet.relative(dirs[i]);
            if (dangerous(mc, to) || dangerous(mc, to.below())) {
                continue;
            }
            boolean stepOver = passable(mc, to) && passable(mc, to.above()) && !passable(mc, to.below());
            boolean hopUp = !passable(mc, to) && passable(mc, to.above()) && passable(mc, to.above(2));
            if (stepOver || hopUp) {
                return keys[i];
            }
        }
        return null;
    }

    private static boolean passable(Minecraft mc, BlockPos q) {
        return mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty();
    }

    private static boolean dangerous(Minecraft mc, BlockPos q) {
        BlockState st = mc.level.getBlockState(q);
        return st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                || st.is(net.minecraft.tags.BlockTags.FIRE) || st.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || st.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH);
    }

    /**
     * Looks for a modded, not-full block right around the player's feet (plaques, decorations: Baritone can
     * misjudge their collision) and adds it to Baritone's blocks to avoid. Returns its id, or null.
     */
    private String avoidCulprit(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 1, 1))) {
            BlockState st = mc.level.getBlockState(q);
            if (st.isAir() || st.isCollisionShapeFullBlock(mc.level, q)) {
                continue;
            }
            net.minecraft.resources.ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            if (!id.getNamespace().equals("minecraft")) {
                BaritoneCompat.avoid(st.getBlock());
                return id.toString();
            }
        }
        return null;
    }

    private void giveUp(Minecraft mc, LocalPlayer p, String why) {
        BaritoneCompat.cancel();
        releaseKeys(mc);
        Overlay.setStatus("I'm stuck (" + why + "), so I've stopped. Waiting for help.");
        finish(mc.level, "stuck: " + why);
    }

    private static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
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
            } else if (level != null && (r.equals("placed") || r.equals("broken") || r.equals("already"))) {
                // Everything is checked again at the end, including blocks that were right to begin with: a later
                // step can knock off a torch or a door whose support it mined.
                BlockState actual = level.getBlockState(s.pos());
                if (!Placement.matches(actual, s.want(), s.props()) || openDiffers(actual, s)) {
                    r = (r.equals("already") ? "changed later: now " : "mismatch: got ")
                            + BlockStateParser.serialize(actual);
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

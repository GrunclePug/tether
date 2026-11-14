package com.grunclepug.tether.compat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import com.google.gson.JsonObject;

/** Baritone integration. Only loaded when the baritone mod is present; call on the client thread. */
public final class BaritoneCompat {
    private static boolean defaultsApplied;

    private BaritoneCompat() {
    }

    /**
     * Runs a Baritone command (the text after its '#' prefix, e.g. "goto 100 64 -20"). The first call switches off
     * block breaking and placing for this session so pathing can't grief builds; "set allowBreak true" undoes it.
     */
    public static JsonObject run(String command) {
        applySafeDefaults();
        boolean ok = baritone().getCommandManager().execute(command);
        JsonObject o = status();
        o.addProperty("accepted", ok);
        return o;
    }

    public static JsonObject status() {
        IBaritone b = baritone();
        IPathingBehavior p = b.getPathingBehavior();
        JsonObject o = new JsonObject();
        o.addProperty("pathing", p.isPathing());
        o.addProperty("goal", p.getGoal() == null ? null : p.getGoal().toString());
        p.estimatedTicksToGoal().ifPresent(t -> o.addProperty("etaTicks", Math.round(t)));
        b.getPathingControlManager().mostRecentInControl()
                .filter(proc -> proc.isActive())
                .ifPresent(proc -> o.addProperty("process", proc.displayName()));
        o.addProperty("busy", p.isPathing() || o.has("process"));
        Settings s = BaritoneAPI.getSettings();
        o.addProperty("allowBreak", s.allowBreak.value);
        o.addProperty("allowPlace", s.allowPlace.value);
        return o;
    }

    /** Paths to within radius blocks of pos, using the same safe defaults as {@link #run}. */
    public static void goNear(net.minecraft.core.BlockPos pos, int radius) {
        applySafeDefaults();
        baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(pos, radius));
    }

    /** Paths onto a block position (to pick up an item lying there). */
    public static void goTo(net.minecraft.core.BlockPos pos) {
        applySafeDefaults();
        baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(pos));
    }

    public static boolean busy() {
        IBaritone b = baritone();
        return b.getPathingBehavior().isPathing()
                || b.getPathingControlManager().mostRecentInControl().filter(proc -> proc.isActive()).isPresent();
    }

    /**
     * Adds a block to Baritone's blocksToAvoid through its own "set" command, so its cached movement data is
     * refreshed too. Lasts for the session.
     */
    public static void avoid(net.minecraft.world.level.block.Block block) {
        Settings s = BaritoneAPI.getSettings();
        java.util.List<net.minecraft.world.level.block.Block> list = new java.util.ArrayList<>(s.blocksToAvoid.value);
        if (list.contains(block)) {
            return;
        }
        list.add(block);
        StringBuilder ids = new StringBuilder();
        for (net.minecraft.world.level.block.Block b : list) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b));
        }
        baritone().getCommandManager().execute("set blocksToAvoid " + ids);
    }

    public static void cancel() {
        baritone().getPathingBehavior().cancelEverything();
    }

    private static void applySafeDefaults() {
        if (defaultsApplied) {
            return;
        }
        Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        defaultsApplied = true;
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }
}

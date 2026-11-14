package com.grunclepug.tether;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Actions that span several ticks: held movement keys and block breaking. Everything here runs on the client
 * thread, driven by {@link #tick(Minecraft)} at the start of each client tick.
 */
public final class Actions {
    private static final Map<KeyMapping, Integer> held = new HashMap<>();
    private static CompletableFuture<JsonObject> moveDone;

    private static BlockPos breakPos;
    private static Direction breakFace;
    private static int breakTicksLeft;
    private static CompletableFuture<JsonObject> breakDone;

    private Actions() {
    }

    /** True while a break is in progress; vanilla's attack handling is suppressed so it doesn't abort it. */
    public static boolean breaking() {
        return breakPos != null;
    }

    static CompletableFuture<JsonObject> hold(Map<KeyMapping, Integer> keys) {
        finishMove("replaced");
        held.putAll(keys);
        moveDone = new CompletableFuture<>();
        return moveDone;
    }

    static CompletableFuture<JsonObject> startBreak(Minecraft mc, BlockPos pos, Direction face) {
        finishBreak(false, "replaced");
        breakPos = pos;
        breakFace = face;
        breakTicksLeft = 20 * 30;
        breakDone = new CompletableFuture<>();
        mc.gameMode.startDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return breakDone;
    }

    static void stopAll(Minecraft mc) {
        finishMove("stopped");
        if (breakPos != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        finishBreak(false, "stopped");
    }

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            stopAll(mc);
            return;
        }
        if (!held.isEmpty()) {
            Iterator<Map.Entry<KeyMapping, Integer>> it = held.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<KeyMapping, Integer> e = it.next();
                if (e.getValue() <= 0) {
                    e.getKey().setDown(false);
                    it.remove();
                } else {
                    e.getKey().setDown(true);
                    e.setValue(e.getValue() - 1);
                }
            }
            if (held.isEmpty()) {
                finishMove("done");
            }
        }
        if (breakPos != null) {
            if (mc.level.getBlockState(breakPos).isAir()) {
                finishBreak(true, "broken");
            } else if (--breakTicksLeft <= 0) {
                mc.gameMode.stopDestroyBlock();
                finishBreak(false, "timed out");
            } else {
                mc.gameMode.continueDestroyBlock(breakPos, breakFace);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private static void finishMove(String result) {
        for (KeyMapping k : held.keySet()) {
            k.setDown(false);
        }
        held.clear();
        if (moveDone != null) {
            JsonObject o = new JsonObject();
            o.addProperty("result", result);
            moveDone.complete(o);
            moveDone = null;
        }
    }

    private static void finishBreak(boolean ok, String result) {
        if (breakDone != null) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("result", result);
            breakDone.complete(o);
        }
        breakPos = null;
        breakDone = null;
    }
}

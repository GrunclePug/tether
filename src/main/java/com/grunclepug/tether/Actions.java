package com.grunclepug.tether;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Actions that span several ticks: held movement keys and the current build job. Everything here runs on the client
 * thread, driven by {@link #tick(Minecraft)} at the start of each client tick.
 */
public final class Actions {
    /** Whether Baritone is loaded; set once at startup. */
    static boolean baritone;

    private static final Map<KeyMapping, Integer> held = new HashMap<>();
    private static CompletableFuture<JsonObject> moveDone;
    private static BuildJob job;
    private static PillarJob pillar;
    /** The world and player the current work started in; a dimension change, death or reconnect stops it. */
    private static Object boundLevel;
    private static Object boundPlayer;
    /** The outcome of the last finished build or pillar, so polling callers still get it. */
    private static JsonObject lastResult;
    private static int jobCounter;

    private Actions() {
    }

    /** True while a job is mining; vanilla's attack handling is suppressed so it doesn't abort the break. */
    public static boolean breaking() {
        return job != null && job.breaking();
    }

    /**
     * Takes sole control of the player for new work: stops held keys, any job or pillar, and Baritone, and remembers
     * which world and player the work belongs to.
     */
    static void claim(Minecraft mc) {
        stopAll(mc);
        if (baritone) {
            com.grunclepug.tether.compat.BaritoneCompat.cancel();
        }
        boundLevel = mc.level;
        boundPlayer = mc.player;
    }

    static CompletableFuture<JsonObject> hold(Minecraft mc, Map<KeyMapping, Integer> keys) {
        claim(mc);
        held.putAll(keys);
        moveDone = new CompletableFuture<>();
        return moveDone;
    }

    static CompletableFuture<JsonObject> startJob(Minecraft mc, BuildJob next) {
        claim(mc);
        job = next;
        lastResult = null;
        int id = ++jobCounter;
        next.future().thenAccept(r -> {
            r.addProperty("job", id);
            lastResult = r;
        });
        return next.future();
    }

    static CompletableFuture<JsonObject> startPillar(Minecraft mc, PillarJob next) {
        claim(mc);
        pillar = next;
        lastResult = null;
        int id = ++jobCounter;
        next.future().thenAccept(r -> {
            r.addProperty("job", id);
            lastResult = r;
        });
        return next.future();
    }

    static JsonObject jobProgress() {
        if (job == null) {
            JsonObject o = new JsonObject();
            o.addProperty("phase", pillar != null ? "pillar" : "idle");
            o.addProperty("job", jobCounter);
            if (lastResult != null) {
                o.add("last", lastResult);
            }
            return o;
        }
        JsonObject o = job.progress();
        o.addProperty("job", jobCounter);
        return o;
    }

    static void stopAll(Minecraft mc) {
        finishMove("stopped");
        if (job != null) {
            job.cancel(mc);
            job = null;
        }
        if (pillar != null) {
            pillar.cancel(mc);
            pillar = null;
        }
    }

    /** The player's pause key: stop acting at once, keep every job's place, wait for resume(). */
    static void pause(Minecraft mc) {
        if (Agent.paused) {
            return;
        }
        Agent.paused = true;
        finishMove("paused");
        if (job != null) {
            job.pause(mc);
        }
        if (pillar != null) {
            pillar.pause(mc);
        }
        if (baritone) {
            com.grunclepug.tether.compat.BaritoneCompat.cancel();
        }
        Overlay.paused();
    }

    static void resume() {
        Agent.paused = false;
        Overlay.setStatus("Resuming where I left off");
    }

    static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 d = target.subtract(p.getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        p.setYRot((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f);
        p.setXRot((float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG));
        p.setYHeadRot(p.getYRot());
    }

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            stopAll(mc);
            Agent.jobRunning = false;
            return;
        }
        boolean working = job != null || pillar != null || !held.isEmpty();
        if (working && (mc.level != boundLevel || mc.player != boundPlayer || mc.player.isDeadOrDying())) {
            // Coordinates mean nothing in another dimension, and a respawned player is somewhere else.
            String why = mc.player.isDeadOrDying() ? "the player died" : "the world or dimension changed";
            stopAll(mc);
            if (baritone) {
                com.grunclepug.tether.compat.BaritoneCompat.cancel();
            }
            Overlay.setStatus("Stopped: " + why + ".");
        }
        Agent.jobRunning = job != null || pillar != null || !held.isEmpty()
                || (baritone && com.grunclepug.tether.compat.BaritoneCompat.busy());
        if (Agent.paused) {
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
        if (job != null) {
            Agent.touch();
            job.tick(mc);
            if (job.finished()) {
                job = null;
            }
        }
        if (pillar != null) {
            Agent.touch();
            pillar.tick(mc);
            if (pillar.finished()) {
                pillar = null;
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
}

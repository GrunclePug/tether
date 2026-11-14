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

    private Actions() {
    }

    /** True while a job is mining; vanilla's attack handling is suppressed so it doesn't abort the break. */
    public static boolean breaking() {
        return job != null && job.breaking();
    }

    static CompletableFuture<JsonObject> hold(Map<KeyMapping, Integer> keys) {
        finishMove("replaced");
        held.putAll(keys);
        moveDone = new CompletableFuture<>();
        return moveDone;
    }

    static CompletableFuture<JsonObject> startJob(Minecraft mc, BuildJob next) {
        if (job != null) {
            job.cancel(mc);
        }
        if (pillar != null) {
            pillar.cancel(mc);
            pillar = null;
        }
        job = next;
        return next.future();
    }

    static CompletableFuture<JsonObject> startPillar(Minecraft mc, PillarJob next) {
        stopAll(mc);
        pillar = next;
        return next.future();
    }

    static JsonObject jobProgress() {
        if (job == null) {
            JsonObject o = new JsonObject();
            o.addProperty("phase", "idle");
            return o;
        }
        return job.progress();
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

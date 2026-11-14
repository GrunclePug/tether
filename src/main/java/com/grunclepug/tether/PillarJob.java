package com.grunclepug.tether;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Builds a column under the player by jumping and placing a block beneath their feet, to reach roofs. The placed
 * positions are returned so the caller can clear the column afterwards (top down, with build and "air").
 */
final class PillarJob {
    private enum Phase { JUMP, RISING, LANDING, DONE }

    private static final int STEP_TIMEOUT = 40;

    private final Item item;
    private int remaining;
    private Phase phase = Phase.JUMP;
    private BlockPos target;
    private int timer;
    private final List<BlockPos> placed = new ArrayList<>();
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();

    PillarJob(Item item, int height) {
        this.item = item;
        this.remaining = height;
    }

    CompletableFuture<JsonObject> future() {
        return done;
    }

    boolean finished() {
        return phase == Phase.DONE;
    }

    void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        switch (phase) {
            case JUMP -> {
                if (remaining <= 0) {
                    finish(mc, "done");
                    return;
                }
                if (!p.onGround()) {
                    return;
                }
                if (!Hotbar.hold(mc, p, item)) {
                    finish(mc, "ran out of blocks");
                    return;
                }
                target = p.blockPosition();
                if (!mc.level.getBlockState(target.above(2)).isAir()) {
                    finish(mc, "blocked above");
                    return;
                }
                mc.options.keyJump.setDown(true);
                phase = Phase.RISING;
                timer = STEP_TIMEOUT;
            }
            case RISING -> {
                mc.options.keyJump.setDown(false);
                if (--timer <= 0) {
                    finish(mc, "jump didn't clear the block");
                    return;
                }
                if (p.getY() < target.getY() + 1.0) {
                    return;
                }
                BlockPos below = target.below();
                Vec3 hit = Vec3.atCenterOf(below).relative(Direction.UP, 0.5);
                p.setXRot(90f);
                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.getYRot(), 90f, p.onGround()));
                InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, Direction.UP, below, false));
                if (!r.consumesAction()) {
                    finish(mc, "placement rejected (" + r.name() + ")");
                    return;
                }
                p.swing(InteractionHand.MAIN_HAND);
                placed.add(target);
                remaining--;
                phase = Phase.LANDING;
                timer = STEP_TIMEOUT;
            }
            case LANDING -> {
                if (p.onGround() || --timer <= 0) {
                    phase = Phase.JUMP;
                }
            }
            case DONE -> {
            }
        }
    }

    /** Lets go of jump; on resume it waits to land and carries on with the same column. */
    void pause(Minecraft mc) {
        mc.options.keyJump.setDown(false);
        if (phase == Phase.RISING) {
            phase = Phase.LANDING;
            timer = STEP_TIMEOUT;
        }
    }

    void cancel(Minecraft mc) {
        finish(mc, "stopped");
    }

    private void finish(Minecraft mc, String outcome) {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        mc.options.keyJump.setDown(false);
        JsonObject o = new JsonObject();
        o.addProperty("outcome", outcome);
        JsonArray list = new JsonArray();
        for (BlockPos b : placed) {
            JsonObject e = new JsonObject();
            e.addProperty("x", b.getX());
            e.addProperty("y", b.getY());
            e.addProperty("z", b.getZ());
            list.add(e);
        }
        o.add("placed", list);
        done.complete(o);
    }
}

package com.grunclepug.tether.mixin;

import com.grunclepug.tether.Actions;
import com.grunclepug.tether.Agent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** Don't open the pause menu on focus loss while an agent is driving; it would block every action. */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void tether$keepRunning(boolean pauseOnly, CallbackInfo ci) {
        if (Agent.active() && !((Minecraft) (Object) this).isWindowActive()) {
            ci.cancel();
        }
    }

    /** Vanilla aborts block breaking every tick the attack key is up; skip that while a tether break runs. */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void tether$keepBreaking(boolean leftClick, CallbackInfo ci) {
        if (Actions.breaking()) {
            ci.cancel();
        }
    }
}

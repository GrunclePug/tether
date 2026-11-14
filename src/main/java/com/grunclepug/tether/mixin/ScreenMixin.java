package com.grunclepug.tether.mixin;

import com.grunclepug.tether.Agent;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    /** Lets an API mouse click count as a shift-click without touching the real keyboard. */
    @Inject(method = "hasShiftDown", at = @At("HEAD"), cancellable = true)
    private static void tether$forceShift(CallbackInfoReturnable<Boolean> cir) {
        if (Agent.forceShift) {
            cir.setReturnValue(true);
        }
    }
}

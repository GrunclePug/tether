package com.grunclepug.tether.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes a container screen's origin so slot positions can be reported in screen coordinates. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int tether$leftPos();

    @Accessor("topPos")
    int tether$topPos();
}

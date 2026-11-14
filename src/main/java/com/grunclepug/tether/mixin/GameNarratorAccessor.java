package com.grunclepug.tether.mixin;

import com.mojang.text2speech.Narrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's speech engine, so status lines can be spoken without turning on the menu narrator. */
@Mixin(GameNarrator.class)
public interface GameNarratorAccessor {
    @Accessor("narrator")
    Narrator tether$narrator();
}

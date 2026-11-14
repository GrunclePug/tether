package com.grunclepug.tether.mixin;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A trapdoor's material, to tell whether it opens by hand (iron doesn't). */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {
    @Invoker("getType")
    BlockSetType tether$type();
}

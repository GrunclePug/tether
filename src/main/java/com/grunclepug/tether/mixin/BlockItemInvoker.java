package com.grunclepug.tether.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the placement planner ask an item which state it would place, exactly as the game decides it. */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState tether$getPlacementState(BlockPlaceContext context);

    @Invoker("canPlace")
    boolean tether$canPlace(BlockPlaceContext context, BlockState state);
}

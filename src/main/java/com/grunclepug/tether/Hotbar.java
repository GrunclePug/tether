package com.grunclepug.tether;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Gets the right item into the player's hand. Needs the player's own inventory menu (no container screen open). */
final class Hotbar {
    private Hotbar() {
    }

    /**
     * Selects a hotbar slot holding the item, swapping it in from the main inventory if needed, or conjuring it in
     * creative mode. Returns false if the player doesn't have it.
     */
    static boolean hold(Minecraft mc, LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).is(item)) {
                inv.selected = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.items.get(i).is(item)) {
                int target = freeSlot(inv);
                // In the inventory menu, slot indices 9-35 are the main inventory; SWAP's button is the hotbar slot.
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, target, ClickType.SWAP, p);
                inv.selected = target;
                return true;
            }
        }
        if (mc.gameMode.hasInfiniteItems()) {
            int target = freeSlot(inv);
            ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
            inv.items.set(target, stack);
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + target);
            inv.selected = target;
            return true;
        }
        return false;
    }

    /** Selects the hotbar item that mines this block fastest, if any beats the bare hand. */
    static void bestTool(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            float speed = inv.items.get(i).getDestroySpeed(state);
            if (speed > bestSpeed) {
                best = i;
                bestSpeed = speed;
            }
        }
        if (best >= 0) {
            inv.selected = best;
        }
    }

    /** A hotbar slot to load a building block into: empty first, then one holding blocks, never a tool if avoidable. */
    private static int freeSlot(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).getItem() instanceof BlockItem) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (!inv.items.get(i).isDamageableItem()) {
                return i;
            }
        }
        return inv.selected;
    }
}

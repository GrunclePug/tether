package com.grunclepug.tether.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tom.storagemod.inventory.TerminalItemStack;
import com.tom.storagemod.menu.StorageTerminalMenu;
import com.tom.storagemod.util.TerminalSyncManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Tom's Simple Storage terminals. Only loaded when toms_storage is present; call on the client thread. */
public final class TomsCompat {
    private TomsCompat() {
    }

    public static boolean isTerminal(AbstractContainerMenu menu) {
        return menu instanceof StorageTerminalMenu;
    }

    /** Every item the open terminal reports, ignoring its search box. Counts are as the terminal shows them. */
    public static JsonArray list(AbstractContainerMenu menu, String match) {
        JsonArray out = new JsonArray();
        List<TerminalItemStack> items = ((StorageTerminalMenu) menu).itemListClient;
        for (int i = 0; i < items.size(); i++) {
            TerminalItemStack t = items.get(i);
            ItemStack stack = t.getStack();
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            if (match != null && !id.contains(match) && !name.toLowerCase().contains(match)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("item", id);
            o.addProperty("name", name);
            o.addProperty("count", t.getQuantity());
            out.add(o);
        }
        return out;
    }

    /**
     * Pulls whole stacks of an item into the player's inventory, like shift-clicking it in the terminal. The item
     * is chosen by exact id, or by index into {@link #list}'s output when the id is ambiguous (different NBT).
     */
    public static int pull(AbstractContainerMenu menu, String item, int index, int stacks) {
        StorageTerminalMenu m = (StorageTerminalMenu) menu;
        TerminalItemStack target = null;
        if (index >= 0 && index < m.itemListClient.size()) {
            target = m.itemListClient.get(index);
        } else {
            for (TerminalItemStack t : m.itemListClient) {
                if (BuiltInRegistries.ITEM.getKey(t.getStack().getItem()).toString().equals(item)) {
                    target = t;
                    break;
                }
            }
        }
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < stacks; i++) {
            m.sync.sendInteract(target, TerminalSyncManager.SlotAction.SHIFT_PULL, false);
        }
        return stacks;
    }
}

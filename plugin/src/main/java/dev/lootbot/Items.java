package dev.lootbot;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;

/**
 * Inventory reading. Player inventory slots are numbered as in the player's own container:
 * 9-35 the main inventory, 36-44 the hotbar (armour and offhand are never touched).
 */
final class Items {

    static final int FIRST = 9;
    static final int LAST = 44;

    private Items() {}

    static boolean blank(ItemStack s) {
        return s == null || s == Container.EMPTY_STACK || s.getAmount() <= 0;
    }

    /** The item id without "minecraft:", "" for an empty slot. */
    static String name(ItemStack s) {
        if (blank(s)) {
            return "";
        }
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        if (d == null) {
            return "";
        }
        String n = d.name();
        return n.startsWith("minecraft:") ? n.substring(10) : n;
    }

    static boolean isShulker(ItemStack s) {
        return name(s).endsWith("shulker_box");
    }

    static List<ItemStack> contents(ItemStack s) {
        if (blank(s)) {
            return List.of();
        }
        List<ItemStack> c = s.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
        return c == null ? List.of() : c;
    }

    static ItemStack stackAt(int slot) {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return inv == null ? Container.EMPTY_STACK : inv.getItemStack(slot);
    }

    static int freeSlots() {
        int n = 0;
        for (int c = FIRST; c <= LAST; c++) {
            if (blank(stackAt(c))) {
                n++;
            }
        }
        return n;
    }

    static int findSlot(Predicate<ItemStack> p) {
        for (int c = 36; c <= LAST; c++) {
            if (!blank(stackAt(c)) && p.test(stackAt(c))) {
                return c;
            }
        }
        for (int c = FIRST; c <= 35; c++) {
            if (!blank(stackAt(c)) && p.test(stackAt(c))) {
                return c;
            }
        }
        return -1;
    }

    /** Item counts in the inventory (9-44), with what is inside shulker boxes counted under "in:<id>". */
    static Map<String, Integer> counts() {
        Map<String, Integer> out = new HashMap<>();
        for (int c = FIRST; c <= LAST; c++) {
            add(out, stackAt(c), "");
        }
        return out;
    }

    static void add(Map<String, Integer> out, ItemStack s, String prefix) {
        if (blank(s)) {
            return;
        }
        out.merge(prefix + name(s), s.getAmount(), Integer::sum);
        if (isShulker(s)) {
            for (ItemStack in : contents(s)) {
                if (!blank(in)) {
                    out.merge("in:" + name(in), in.getAmount(), Integer::sum);
                }
            }
        }
    }

    /** What b has more of than a (the loot gained between two snapshots). */
    static Map<String, Integer> gained(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new HashMap<>();
        for (var e : b.entrySet()) {
            int d = e.getValue() - a.getOrDefault(e.getKey(), 0);
            if (d > 0) {
                out.put(e.getKey(), d);
            }
        }
        return out;
    }
}

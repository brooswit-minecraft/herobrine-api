package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Your inventory and the container you currently have open, as the client sees them. */
final class InventoryBindings {
    private InventoryBindings() {}

    static void register(ApiContext ctx) {
        read(ctx, "player.inventory",
                "Non-empty inventory slots (0-35 main, 36-39 armor feet-to-head, 40 offhand), the selected hotbar slot and the carried stack",
                q -> {
                    LocalPlayer p = Game.player();
                    HolderLookup.Provider registries = p.level().registryAccess();
                    Inventory inv = p.getInventory();
                    List<Map<String, Object>> slots = new ArrayList<>();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        Map<String, Object> stack = describe(inv.getItem(i), registries);
                        if (stack == null) continue;
                        stack.put("slot", i);
                        slots.add(stack);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("selectedSlot", inv.selected);
                    m.put("slotCount", inv.getContainerSize());
                    m.put("slots", slots);
                    m.put("carried", describe(p.containerMenu.getCarried(), registries));
                    return m;
                });

        read(ctx, "container.open",
                "The menu you have open (chest, furnace, your own inventory screen, ...): its non-empty slots with their GUI positions, and the carried stack",
                q -> {
                    LocalPlayer p = Game.player();
                    HolderLookup.Provider registries = p.level().registryAccess();
                    AbstractContainerMenu menu = p.containerMenu;
                    List<Map<String, Object>> slots = new ArrayList<>();
                    for (Slot s : menu.slots) {
                        Map<String, Object> stack = describe(s.getItem(), registries);
                        if (stack == null) continue;
                        stack.put("index", s.index);
                        stack.put("x", s.x);
                        stack.put("y", s.y);
                        slots.add(stack);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("containerId", menu.containerId);
                    m.put("isPlayerInventory", menu == p.inventoryMenu);
                    m.put("menuType", menuType(menu));
                    m.put("title", Minecraft.getInstance().screen == null ? null : Minecraft.getInstance().screen.getTitle().getString());
                    m.put("slotCount", menu.slots.size());
                    m.put("slots", slots);
                    m.put("carried", describe(menu.getCarried(), registries));
                    return m;
                });
    }

    /**
     * An item stack as a map: id, count, damage, display name, and "nbt" - the stack serialized exactly as
     * the game saves it (SNBT, including all data components such as enchantments and lore). Null if empty.
     */
    static Map<String, Object> describe(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        m.put("count", stack.getCount());
        m.put("damage", stack.getDamageValue());
        m.put("maxDamage", stack.getMaxDamage());
        m.put("name", stack.getHoverName().getString());
        Tag tag = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack).result().orElse(null);
        m.put("nbt", tag == null ? null : tag.toString());
        return m;
    }

    private static String menuType(AbstractContainerMenu menu) {
        try {
            return BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        } catch (UnsupportedOperationException e) {
            return null; // the player's own inventory menu has no registered type
        }
    }

    private static void read(ApiContext ctx, String id, String desc, Function<Params, Object> body) {
        ctx.resources.register(id, new ResourceDef(id, desc, Availability.SERVER_SYNCED, body));
    }
}

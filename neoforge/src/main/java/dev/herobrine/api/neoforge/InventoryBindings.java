package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Your inventory and the container you currently have open, as the client sees them. */
final class InventoryBindings {
    private InventoryBindings() {}

    static void register(ApiContext ctx) {
        registerActions(ctx);
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
                "The menu you have open (chest, furnace, your own inventory screen, ...): its non-empty slots with their GUI positions, the carried stack, and the crafting grid layout (result slot, size) if it has one",
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
                    m.put("crafting", CraftingBindings.gridInfo(menu));
                    return m;
                });
    }

    // ---- management: every inventory change in Minecraft is a click on a menu slot ----

    private static final List<String> CLICK_TYPES = List.of("PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL");
    private static final ParamDef CONTAINER_ID = ParamDef.optional("containerId", "integer",
            "Menu id from container.open; the click is refused if a different menu is open now", null);

    private static void registerActions(ApiContext ctx) {
        ctx.actions.register("inventory.select_slot", ActionDef.builder("inventory.select_slot", "Select a hotbar slot (0-8)")
                .param(ParamDef.required("slot", "integer", "Hotbar slot 0-8"))
                .handler(p -> {
                    int slot = Game.inRange(p.integer("slot"), 0, 8, "slot");
                    Game.player().getInventory().selected = slot;
                    return Map.of("selectedSlot", slot);
                }));

        ctx.actions.register("inventory.click", ActionDef.builder("inventory.click",
                        "Click a menu slot exactly like the mouse does (MultiPlayerGameMode.handleInventoryMouseClick). slot is the MENU slot index from "
                                + "container.open (not the player.inventory index); -999 clicks outside the window. "
                                + "PICKUP: button 0 = left click, 1 = right click. QUICK_MOVE: shift-click. SWAP: button = hotbar 0-8, or 40 for offhand. "
                                + "THROW: button 0 = one item, 1 = whole stack. CLONE: button 2. PICKUP_ALL: double-click. QUICK_CRAFT: drag steps")
                .param(ParamDef.required("slot", "integer", "Menu slot index, or -999 for outside the window"))
                .param(ParamDef.optional("button", "integer", "Mouse button / hotbar index", 0))
                .param(new ParamDef("type", "string", false, "Click type", "PICKUP", CLICK_TYPES))
                .param(CONTAINER_ID)
                .handler(p -> click(p, p.integer("slot"), Game.inRange(p.integer("button", 0), 0, 40, "button"),
                        ClickType.valueOf(p.string("type", "PICKUP")))));

        ctx.actions.register("inventory.move", ActionDef.builder("inventory.move", "Shift-click a slot (move its stack to the other part of the menu)")
                .param(ParamDef.required("slot", "integer", "Menu slot index"))
                .param(CONTAINER_ID)
                .handler(p -> click(p, p.integer("slot"), 0, ClickType.QUICK_MOVE)));

        ctx.actions.register("inventory.swap", ActionDef.builder("inventory.swap", "Swap a slot with a hotbar slot, or with the offhand (hotbar 40)")
                .param(ParamDef.required("slot", "integer", "Menu slot index"))
                .param(ParamDef.required("hotbar", "integer", "Hotbar slot 0-8, or 40 for the offhand"))
                .param(CONTAINER_ID)
                .handler(p -> {
                    int hotbar = p.integer("hotbar");
                    if (hotbar != 40) Game.inRange(hotbar, 0, 8, "hotbar");
                    return click(p, p.integer("slot"), hotbar, ClickType.SWAP);
                }));

        ctx.actions.register("inventory.drop", ActionDef.builder("inventory.drop", "Drop one item or the whole stack from a slot")
                .param(ParamDef.required("slot", "integer", "Menu slot index"))
                .param(ParamDef.optional("all", "boolean", "Drop the whole stack instead of one item", false))
                .param(CONTAINER_ID)
                .handler(p -> click(p, p.integer("slot"), p.bool("all", false) ? 1 : 0, ClickType.THROW)));

        ctx.actions.register("inventory.close", ActionDef.builder("inventory.close", "Close the open container or screen")
                .handler(p -> {
                    Game.player().closeContainer();
                    return Map.of("closed", true);
                }));
    }

    /** Sends one container click. The server's response arrives on later ticks: read container.open afterwards. */
    static Object click(Params p, int slot, int button, ClickType type) {
        LocalPlayer player = Game.player();
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null) throw ApiException.unavailable("Not connected to a game");
        AbstractContainerMenu menu = player.containerMenu;
        if (p.has("containerId") && p.integer("containerId") != menu.containerId)
            throw ApiException.unavailable("The open container changed (it is now " + menu.containerId + "); read container.open again");
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size()))
            throw ApiException.badRequest("slot must be -999 or between 0 and " + (menu.slots.size() - 1));
        gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player);
        return Map.of("containerId", menu.containerId, "slot", slot, "button", button, "type", type.name());
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

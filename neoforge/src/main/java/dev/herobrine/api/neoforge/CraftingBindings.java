package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.ItemStack;

/**
 * Crafting the way the recipe book does it: list recipes the server synced, ask the server to fill the
 * crafting grid (MultiPlayerGameMode.handlePlaceRecipe), then take the result. Placing and taking are
 * separate calls because the server fills the grid on a later tick.
 */
final class CraftingBindings {
    private CraftingBindings() {}

    static void register(ApiContext ctx) {
        ctx.resources.register("recipes", new ResourceDef("recipes",
                "Recipes the server synced to the client. Params: output (result item id), type (recipe type, e.g. minecraft:crafting_shaped), "
                        + "unlockedOnly (true = only recipes in your recipe book), offset, limit (default 100, max 500)",
                Availability.SERVER_SYNCED, q -> {
                    LocalPlayer player = Game.player();
                    ClientPacketListener conn = player.connection;
                    RegistryAccess access = Game.level().registryAccess();
                    String output = q.has("output") ? Validation.resourceId(q.string("output")) : null;
                    String type = q.has("type") ? Validation.resourceId(q.string("type")) : null;
                    boolean unlockedOnly = q.bool("unlockedOnly", false);
                    List<RecipeHolder<?>> matches = new ArrayList<>();
                    for (RecipeHolder<?> h : conn.getRecipeManager().getRecipes()) {
                        Recipe<?> r = h.value();
                        if (type != null && !BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).toString().equals(type)) continue;
                        if (output != null && !itemId(r.getResultItem(access)).equals(output)) continue;
                        if (unlockedOnly && !player.getRecipeBook().contains(h)) continue;
                        matches.add(h);
                    }
                    Page<RecipeHolder<?>> page = Page.of(matches, q, 100, 500);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (RecipeHolder<?> h : page.items()) items.add(describe(h, access, player.getRecipeBook().contains(h)));
                    return new Page<>(items, page.total(), page.offset(), page.limit(), page.nextOffset());
                }));

        ctx.actions.register("crafting.place", ActionDef.builder("crafting.place",
                        "Ask the server to fill the open crafting grid for a recipe, like clicking it in the recipe book "
                                + "(works in your inventory's 2x2 grid, a crafting table, furnaces, ...). Then read container.open and call crafting.take")
                .param(ParamDef.required("recipe", "string", "Recipe id from the recipes resource, e.g. minecraft:stick"))
                .param(ParamDef.optional("all", "boolean", "Fill for the maximum number of crafts (shift-click)", false))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open; refused if a different menu is open", null))
                .handler(p -> {
                    LocalPlayer player = Game.player();
                    MultiPlayerGameMode gameMode = Game.gameMode();
                    AbstractContainerMenu menu = Game.menu(p);
                    if (!(menu instanceof RecipeBookMenu<?, ?>)) throw ApiException.unavailable("The open menu has no crafting grid");
                    ResourceLocation id = ResourceLocation.tryParse(p.string("recipe"));
                    if (id == null) throw ApiException.badRequest("Invalid recipe id");
                    RecipeHolder<?> holder = player.connection.getRecipeManager().byKey(id)
                            .orElseThrow(() -> ApiException.notFound("Unknown recipe " + id));
                    gameMode.handlePlaceRecipe(menu.containerId, holder, p.bool("all", false));
                    return Map.of("containerId", menu.containerId, "recipe", id.toString(), "all", p.bool("all", false));
                }));

        ctx.actions.register("crafting.take", ActionDef.builder("crafting.take",
                        "Take the crafted result from the open crafting menu: shift-click (all=true, crafts as many as the grid allows) "
                                + "or pick one craft up onto the cursor")
                .param(ParamDef.optional("all", "boolean", "Shift-click the result slot", true))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open; refused if a different menu is open", null))
                .handler(p -> {
                    AbstractContainerMenu menu = Game.menu(p);
                    if (!(menu instanceof RecipeBookMenu<?, ?> book)) throw ApiException.unavailable("The open menu has no crafting grid");
                    return InventoryBindings.click(p, book.getResultSlotIndex(), 0,
                            p.bool("all", true) ? ClickType.QUICK_MOVE : ClickType.PICKUP);
                }));
    }

    /** Adds the crafting grid layout to container.open when the open menu has one. */
    static Map<String, Object> gridInfo(AbstractContainerMenu menu) {
        if (!(menu instanceof RecipeBookMenu<?, ?> book)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultSlot", book.getResultSlotIndex());
        m.put("gridWidth", book.getGridWidth());
        m.put("gridHeight", book.getGridHeight());
        m.put("size", book.getSize());
        return m;
    }

    private static Map<String, Object> describe(RecipeHolder<?> h, RegistryAccess access, boolean unlocked) {
        Recipe<?> r = h.value();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id().toString());
        m.put("type", BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).toString());
        ItemStack result = r.getResultItem(access);
        m.put("result", Map.of("id", itemId(result), "count", result.getCount()));
        List<List<String>> ingredients = new ArrayList<>();
        for (Ingredient ing : r.getIngredients()) {
            List<String> options = new ArrayList<>();
            for (ItemStack s : ing.getItems()) {
                String id = itemId(s);
                if (!options.contains(id)) options.add(id);
            }
            ingredients.add(options); // empty list = an empty cell in a shaped recipe
        }
        m.put("ingredients", ingredients);
        if (r instanceof ShapedRecipe shaped) {
            m.put("width", shaped.getWidth());
            m.put("height", shaped.getHeight());
        }
        m.put("group", r.getGroup());
        m.put("unlocked", unlocked);
        return m;
    }

    private static String itemId(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
}

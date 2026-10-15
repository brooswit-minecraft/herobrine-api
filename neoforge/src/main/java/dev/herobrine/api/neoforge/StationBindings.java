package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Crafting stations other than the plain grid: what each open menu is showing (progress, options, offers)
 * and the few inputs that are not slot clicks. Moving items in and out is always inventory.click / move /
 * swap; fuel, ingredients and results are just slots, named in each station's "roles".
 */
final class StationBindings {
    private StationBindings() {}

    static void register(ApiContext ctx) {
        ctx.actions.register("container.button", ActionDef.builder("container.button",
                        "Press a menu button (MultiPlayerGameMode.handleInventoryButtonClick): enchanting table option 0-2, "
                                + "stonecutter recipe index, loom pattern index, lectern page controls. See container.open -> station")
                .param(ParamDef.required("buttonId", "integer", "Button id"))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open; refused if a different menu is open", null))
                .handler(p -> {
                    AbstractContainerMenu menu = Game.menu(p);
                    int button = Game.inRange(p.integer("buttonId"), 0, 1000, "buttonId");
                    Game.gameMode().handleInventoryButtonClick(menu.containerId, button);
                    return Map.of("containerId", menu.containerId, "buttonId", button);
                }));

        ctx.actions.register("anvil.rename", ActionDef.builder("anvil.rename", "Set the new name in the open anvil (max 50 characters)")
                .param(ParamDef.required("name", "string", "New item name"))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open", null))
                .handler(p -> {
                    if (!(Game.menu(p) instanceof AnvilMenu anvil)) throw ApiException.unavailable("The open menu is not an anvil");
                    String name = p.string("name");
                    if (name.length() > 50) throw ApiException.badRequest("Name exceeds 50 characters");
                    Validation.settingValue(name);
                    anvil.setItemName(name);
                    Game.player().connection.send(new ServerboundRenameItemPacket(name));
                    return Map.of("name", name);
                }));

        ctx.actions.register("merchant.select_trade", ActionDef.builder("merchant.select_trade",
                        "Select a villager trade in the open trading menu (fills the input slots from your inventory); then take slot 2 with inventory.move")
                .param(ParamDef.required("index", "integer", "Trade index from container.open -> station.offers"))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open", null))
                .handler(p -> {
                    if (!(Game.menu(p) instanceof MerchantMenu merchant)) throw ApiException.unavailable("The open menu is not a trading menu");
                    int index = p.integer("index");
                    if (index < 0 || index >= merchant.getOffers().size())
                        throw ApiException.badRequest("index must be between 0 and " + (merchant.getOffers().size() - 1));
                    merchant.setSelectionHint(index);
                    merchant.tryMoveItems(index);
                    Game.player().connection.send(new ServerboundSelectTradePacket(index));
                    return Map.of("index", index);
                }));

        ctx.actions.register("beacon.set_effects", ActionDef.builder("beacon.set_effects",
                        "Choose the open beacon's effects (needs a payment item in its slot; the screen would then close itself - use inventory.close)")
                .param(ParamDef.optional("primary", "string", "Effect id, e.g. minecraft:speed", null))
                .param(ParamDef.optional("secondary", "string", "Effect id, e.g. minecraft:regeneration", null))
                .param(ParamDef.optional("containerId", "integer", "Menu id from container.open", null))
                .handler(p -> {
                    if (!(Game.menu(p) instanceof BeaconMenu)) throw ApiException.unavailable("The open menu is not a beacon");
                    Optional<Holder<MobEffect>> primary = effect(p, "primary");
                    Optional<Holder<MobEffect>> secondary = effect(p, "secondary");
                    Game.player().connection.send(new ServerboundSetBeaconPacket(primary, secondary));
                    return Map.of("primary", p.string("primary", ""), "secondary", p.string("secondary", ""));
                }));
    }

    /** The "station" part of container.open: null if the open menu is a plain container. */
    static Map<String, Object> describe(AbstractContainerMenu menu, RegistryAccess access) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (menu instanceof AbstractFurnaceMenu f) {
            m.put("kind", "furnace");
            m.put("roles", Map.of(0, "input", 1, "fuel", 2, "result"));
            m.put("lit", f.isLit());
            m.put("litProgress", f.getLitProgress());
            m.put("burnProgress", f.getBurnProgress());
        } else if (menu instanceof BrewingStandMenu b) {
            m.put("kind", "brewing_stand");
            m.put("roles", Map.of(0, "bottle", 1, "bottle", 2, "bottle", 3, "ingredient", 4, "blaze_powder_fuel"));
            m.put("fuel", b.getFuel());
            m.put("brewingTicks", b.getBrewingTicks());
        } else if (menu instanceof EnchantmentMenu e) {
            m.put("kind", "enchanting_table");
            m.put("roles", Map.of(0, "item", 1, "lapis_lazuli"));
            m.put("lapis", e.getGoldCount());
            m.put("seed", e.getEnchantmentSeed());
            List<Map<String, Object>> options = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("buttonId", i);
                o.put("levelCost", e.costs[i]);
                o.put("hintEnchantment", enchantmentId(access, e.enchantClue[i]));
                o.put("hintLevel", e.levelClue[i]);
                options.add(o);
            }
            m.put("options", options);
        } else if (menu instanceof AnvilMenu a) {
            m.put("kind", "anvil");
            m.put("roles", Map.of(0, "left", 1, "right", 2, "result"));
            m.put("levelCost", a.getCost());
        } else if (menu instanceof GrindstoneMenu) {
            m.put("kind", "grindstone");
            m.put("roles", Map.of(0, "top", 1, "bottom", 2, "result"));
        } else if (menu instanceof StonecutterMenu s) {
            m.put("kind", "stonecutter");
            m.put("roles", Map.of(0, "input", 1, "result"));
            m.put("selected", s.getSelectedRecipeIndex());
            List<Map<String, Object>> recipes = new ArrayList<>();
            for (int i = 0; i < s.getRecipes().size(); i++) {
                var result = s.getRecipes().get(i).value().getResultItem(access);
                recipes.add(Map.of("buttonId", i, "result", BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), "count", result.getCount()));
            }
            m.put("recipes", recipes);
        } else if (menu instanceof LoomMenu l) {
            m.put("kind", "loom");
            m.put("roles", Map.of(0, "banner", 1, "dye", 2, "pattern_item", 3, "result"));
            m.put("selected", l.getSelectedBannerPatternIndex());
            List<String> patterns = new ArrayList<>();
            for (var h : l.getSelectablePatterns()) patterns.add(h.unwrapKey().map(k -> k.location().toString()).orElse(null));
            m.put("patterns", patterns);
        } else if (menu instanceof SmithingMenu) {
            m.put("kind", "smithing_table");
            m.put("roles", Map.of(0, "template", 1, "base", 2, "addition", 3, "result"));
        } else if (menu instanceof CartographyTableMenu) {
            m.put("kind", "cartography_table");
            m.put("roles", Map.of(0, "map", 1, "paper_or_glass_pane", 2, "result"));
        } else if (menu instanceof BeaconMenu b) {
            m.put("kind", "beacon");
            m.put("roles", Map.of(0, "payment"));
            m.put("levels", b.getLevels());
            m.put("hasPayment", b.hasPayment());
            m.put("primary", b.getPrimaryEffect() == null ? null : b.getPrimaryEffect().unwrapKey().map(k -> k.location().toString()).orElse(null));
            m.put("secondary", b.getSecondaryEffect() == null ? null : b.getSecondaryEffect().unwrapKey().map(k -> k.location().toString()).orElse(null));
        } else if (menu instanceof MerchantMenu t) {
            m.put("kind", "merchant");
            m.put("roles", Map.of(0, "buy_a", 1, "buy_b", 2, "result"));
            m.put("traderLevel", t.getTraderLevel());
            m.put("traderXp", t.getTraderXp());
            List<Map<String, Object>> offers = new ArrayList<>();
            int i = 0;
            for (MerchantOffer o : t.getOffers()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("index", i++);
                om.put("buyA", InventoryBindings.describe(o.getCostA(), access));
                om.put("buyB", InventoryBindings.describe(o.getCostB(), access));
                om.put("sell", InventoryBindings.describe(o.getResult(), access));
                om.put("uses", o.getUses());
                om.put("maxUses", o.getMaxUses());
                om.put("outOfStock", o.isOutOfStock());
                om.put("xp", o.getXp());
                offers.add(om);
            }
            m.put("offers", offers);
        } else {
            return null;
        }
        return m;
    }

    private static String enchantmentId(RegistryAccess access, int id) {
        if (id < 0) return null;
        return access.registryOrThrow(Registries.ENCHANTMENT).getHolder(id)
                .flatMap(h -> h.unwrapKey()).map(k -> k.location().toString()).orElse(null);
    }

    private static Optional<Holder<MobEffect>> effect(Params p, String name) {
        if (!p.has(name)) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(Validation.resourceId(p.string(name)));
        if (id == null) throw ApiException.badRequest("Invalid effect id");
        Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, id))
                .orElseThrow(() -> ApiException.notFound("Unknown effect " + id));
        return Optional.of(holder);
    }
}

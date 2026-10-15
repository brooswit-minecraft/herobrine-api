package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** Generic, registry-backed discovery of mods and everything they register (spec 7.1). */
final class ModBindings {
    private ModBindings() {}

    /** Built-in registries are frozen after load, so their id lists can be cached (spec 13.4). */
    private static final Map<ResourceLocation, List<String>> BUILTIN_IDS = new ConcurrentHashMap<>();

    static void register(ApiContext ctx) {
        ctx.mods = ModBindings::modSummaries;

        ctx.resources.register("mods.detail", new ResourceDef("mods.detail",
                "Per-mod metadata, dependencies and counts of registered objects per registry. Params: modId, offset, limit",
                Availability.LOCAL, q -> {
                    String only = q.string("modId", null);
                    List<Map<String, Object>> all = new ArrayList<>();
                    for (var m : ModList.get().getMods()) {
                        if (only != null && !m.getModId().equals(only)) continue;
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("id", m.getModId());
                        e.put("name", m.getDisplayName());
                        e.put("version", m.getVersion().toString());
                        List<Map<String, String>> deps = new ArrayList<>();
                        m.getDependencies().forEach(d -> deps.add(Map.of("modId", d.getModId(), "versionRange", String.valueOf(d.getVersionRange()))));
                        e.put("dependencies", deps);
                        e.put("registered", registeredCounts(m.getModId()));
                        all.add(e);
                    }
                    return Page.of(all, q, 50, 200);
                }));

        ctx.resources.register("registry.entries", new ResourceDef("registry.entries",
                "Entries of any registry (built-in or dynamic), including modded ones. Params: registry (e.g. minecraft:block), namespace, offset, limit",
                Availability.LOCAL, q -> {
                    ResourceLocation key = ResourceLocation.tryParse(q.string("registry"));
                    if (key == null) throw ApiException.badRequest("Invalid registry id");
                    String ns = q.string("namespace", null);
                    List<String> ids = idsOf(key);
                    if (ns != null) ids = ids.stream().filter(i -> i.startsWith(ns + ":")).toList();
                    return Page.of(ids, q, 200, 1000);
                }));

        ctx.resources.register("commands.known", new ResourceDef("commands.known",
                "Root command names the server has told this client about",
                Availability.SERVER_SYNCED, q -> {
                    var conn = Minecraft.getInstance().getConnection();
                    if (conn == null) throw ApiException.unavailable("Not connected");
                    return conn.getCommands().getRoot().getChildren().stream().map(n -> n.getName()).sorted().toList();
                }));

        ctx.resources.register("input.keybindings", new ResourceDef("input.keybindings",
                "All registered key mappings (vanilla and modded) with category and current binding",
                Availability.LOCAL, q -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (KeyMapping k : Minecraft.getInstance().options.keyMappings) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("name", k.getName());
                        e.put("category", k.getCategory());
                        e.put("binding", k.getTranslatedKeyMessage().getString());
                        e.put("isDown", k.isDown());
                        e.put("isDefault", k.isDefault());
                        out.add(e);
                    }
                    return out;
                }));
    }

    private static List<Map<String, String>> modSummaries() {
        List<Map<String, String>> l = new ArrayList<>();
        ModList.get().getMods().forEach(m -> l.add(Map.of("id", m.getModId(), "name", m.getDisplayName(), "version", m.getVersion().toString())));
        return l;
    }

    /** registry id -> number of entries in that registry under the mod's namespace. */
    private static Map<String, Integer> registeredCounts(String modId) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Registry<?> reg : BuiltInRegistries.REGISTRY) {
            int n = 0;
            for (ResourceLocation id : reg.keySet()) if (id.getNamespace().equals(modId)) n++;
            if (n > 0) counts.put(reg.key().location().toString(), n);
        }
        return counts;
    }

    private static List<String> idsOf(ResourceLocation registryId) {
        List<String> cached = BUILTIN_IDS.get(registryId);
        if (cached != null) return cached;
        Registry<?> reg = BuiltInRegistries.REGISTRY.get(registryId);
        if (reg != null) {
            List<String> ids = reg.keySet().stream().map(ResourceLocation::toString).sorted().toList();
            BUILTIN_IDS.put(registryId, ids);
            return ids;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) throw ApiException.unavailable("Unknown built-in registry; dynamic registries need a loaded world");
        var dyn = level.registryAccess().registry(ResourceKey.createRegistryKey(registryId));
        if (dyn.isEmpty()) throw ApiException.notFound("Unknown registry " + registryId);
        return dyn.get().keySet().stream().map(ResourceLocation::toString).sorted().toList();
    }
}

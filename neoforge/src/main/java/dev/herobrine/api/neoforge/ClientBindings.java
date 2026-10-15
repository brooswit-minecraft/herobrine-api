package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

/**
 * Initial resource/action set. This is a seed, not the full inventory in the design doc:
 * see docs/COVERAGE.md for what is implemented and what remains.
 */
final class ClientBindings {
    private ClientBindings() {}

    static void register(ApiContext ctx) {
        ctx.observedTick = () -> {
            Level l = Minecraft.getInstance().level;
            return l == null ? -1 : l.getGameTime();
        };
        ctx.capability("world.loadedChunks", () -> Minecraft.getInstance().level != null);
        ctx.capability("world.unloadedChunks", () -> false);   // server-only without a companion
        ctx.capability("entities.serverAI", () -> false);      // server-only without a companion
        ctx.capability("player.input", () -> Minecraft.getInstance().player != null);

        ctx.resources.register("client.info", new ResourceDef("client.info", "Game version, window and connection basics",
                Availability.LOCAL, q -> {
                    Minecraft mc = Minecraft.getInstance();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("minecraftVersion", SharedConstants.getCurrentVersion().getName());
                    m.put("fps", mc.getFps());
                    m.put("screen", mc.screen == null ? null : mc.screen.getClass().getName());
                    m.put("windowWidth", mc.getWindow().getWidth());
                    m.put("windowHeight", mc.getWindow().getHeight());
                    m.put("guiScale", mc.getWindow().getGuiScale());
                    m.put("inWorld", mc.level != null);
                    m.put("singleplayer", mc.hasSingleplayerServer());
                    return m;
                }));

        ctx.resources.register("player.local", new ResourceDef("player.local", "Local player position, vitals and movement state",
                Availability.SERVER_SYNCED, q -> {
                    LocalPlayer p = requirePlayer();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getGameProfile().getName());
                    m.put("uuid", p.getUUID().toString());
                    m.put("position", List.of(p.getX(), p.getY(), p.getZ()));
                    m.put("yaw", p.getYRot());
                    m.put("pitch", p.getXRot());
                    m.put("health", p.getHealth());
                    m.put("maxHealth", p.getMaxHealth());
                    m.put("food", p.getFoodData().getFoodLevel());
                    m.put("saturation", p.getFoodData().getSaturationLevel());
                    m.put("xpLevel", p.experienceLevel);
                    m.put("onGround", p.onGround());
                    m.put("sprinting", p.isSprinting());
                    m.put("sneaking", p.isShiftKeyDown());
                    m.put("selectedSlot", p.getInventory().selected);
                    return m;
                }));

        ctx.resources.register("world.info", new ResourceDef("world.info", "Dimension, time and weather as known to the client",
                Availability.SERVER_SYNCED, q -> {
                    Level l = Minecraft.getInstance().level;
                    if (l == null) throw ApiException.unavailable("Not in a world");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dimension", l.dimension().location().toString());
                    m.put("gameTime", l.getGameTime());
                    m.put("dayTime", l.getDayTime());
                    m.put("raining", l.isRaining());
                    m.put("thundering", l.isThundering());
                    m.put("minY", l.getMinBuildHeight());
                    m.put("maxY", l.getMaxBuildHeight());
                    return m;
                }));

        // registry.list is superseded by registry.entries in ModBindings but kept for names-only discovery.
        ctx.resources.register("registry.list", new ResourceDef("registry.list", "Names of all built-in registries",
                Availability.LOCAL, q -> BuiltInRegistries.REGISTRY.keySet().stream().map(Object::toString).sorted().toList()));

        ctx.actions.register("chat.send", ActionDef.builder("chat.send",
                        "Send a plain chat message (max 256 chars; no '/' commands, control characters or '\u00a7')")
                .param(ParamDef.required("message", "string", "Text to send"))
                .availability(Availability.CLIENT_REQUEST)
                .handler(p -> {
                    LocalPlayer pl = requirePlayer();
                    pl.connection.sendChat(Validation.chatText(p.string("message")));
                    return Map.of("sent", true);
                }));

        ctx.actions.register("chat.command", ActionDef.builder("chat.command",
                        "Run a command on the server, without the leading '/'")
                .param(ParamDef.required("command", "string", "Command text, e.g. 'time set day'"))
                .availability(Availability.CLIENT_REQUEST)
                .handler(p -> {
                    LocalPlayer pl = requirePlayer();
                    pl.connection.sendCommand(Validation.chatText(p.string("command")));
                    return Map.of("sent", true);
                }));

        ctx.actions.register("player.look", ActionDef.builder("player.look", "Set the player's view angles")
                .param(ParamDef.optional("yaw", "number", "Yaw in degrees", null))
                .param(ParamDef.optional("pitch", "number", "Pitch in degrees (-90..90)", null))
                .handler(p -> {
                    LocalPlayer pl = requirePlayer();
                    if (p.has("yaw")) pl.setYRot((float) p.number("yaw"));
                    if (p.has("pitch")) pl.setXRot((float) Math.max(-90, Math.min(90, p.number("pitch"))));
                    return Map.of("yaw", pl.getYRot(), "pitch", pl.getXRot());
                }));

        ctx.actions.register("input.key", ActionDef.builder("input.key",
                        "Press or release a key mapping (e.g. key.forward, key.jump, key.attack), like KeyMapping.setDown. "
                                + "The game keeps it held until you release it; keys are released when the API stops")
                .param(ParamDef.required("key", "string", "Key mapping name, e.g. key.jump"))
                .param(ParamDef.required("down", "boolean", "true to press, false to release"))
                .handler(p -> {
                    requirePlayer();
                    KeyMapping km = findKey(p.string("key"));
                    boolean down = p.bool("down", true);
                    km.setDown(down);
                    if (down) HELD.add(km); else HELD.remove(km);
                    return Map.of("key", km.getName(), "down", km.isDown());
                }));

        ctx.actions.register("input.click", ActionDef.builder("input.click",
                        "One click/tap of a key mapping (KeyMapping.click): use for attack, use-item, hotbar, drop, inventory")
                .param(ParamDef.required("key", "string", "Key mapping name, e.g. key.attack"))
                .handler(p -> {
                    requirePlayer();
                    KeyMapping km = findKey(p.string("key"));
                    KeyMapping.click(km.getKey());
                    return Map.of("key", km.getName(), "clicked", true);
                }));
    }

    /** Keys this API pressed and has not released; the only state kept, so they can be let go on stop. */
    private static final java.util.Set<KeyMapping> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Releases every key the API is holding. Call from the client thread. */
    static void releaseAllKeys() {
        for (KeyMapping km : HELD) km.setDown(false);
        HELD.clear();
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw ApiException.unavailable("No local player (not in a world)");
        return p;
    }

    private static KeyMapping findKey(String name) {
        for (KeyMapping k : Minecraft.getInstance().options.keyMappings)
            if (k.getName().equals(name)) return k;
        throw ApiException.badRequest("Unknown key mapping: " + name);
    }
}

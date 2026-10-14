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
                Availability.SERVER_SYNCED, "read.players", q -> {
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
                Availability.SERVER_SYNCED, "read.world", q -> {
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

        ctx.actions.register("chat.send", ActionDef.builder("chat.send", "Send a chat message, or a command if it starts with '/'")
                .param(ParamDef.required("message", "string", "Text to send"))
                .permission("control.chat")
                .availability(Availability.CLIENT_REQUEST)
                .handler(p -> {
                    LocalPlayer pl = requirePlayer();
                    String msg = p.string("message");
                    if (msg.startsWith("/")) pl.connection.sendCommand(msg.substring(1));
                    else pl.connection.sendChat(msg);
                    return Map.of("sent", true);
                }));

        ctx.actions.register("player.look", ActionDef.builder("player.look", "Set the player's view angles")
                .param(ParamDef.optional("yaw", "number", "Yaw in degrees", null))
                .param(ParamDef.optional("pitch", "number", "Pitch in degrees (-90..90)", null))
                .lock("player.rotation")
                .handler(p -> {
                    LocalPlayer pl = requirePlayer();
                    if (p.has("yaw")) pl.setYRot((float) p.number("yaw"));
                    if (p.has("pitch")) pl.setXRot((float) Math.max(-90, Math.min(90, p.number("pitch"))));
                    return Map.of("yaw", pl.getYRot(), "pitch", pl.getXRot());
                }));

        ctx.actions.register("input.hold", ActionDef.builder("input.hold",
                        "Hold a key mapping (e.g. key.forward, key.jump, key.attack) for N ticks; returns a cancellable operation")
                .param(ParamDef.required("key", "string", "Key mapping name, e.g. key.jump"))
                .param(ParamDef.optional("ticks", "integer", "Duration in ticks", 1))
                .permission("control.input")
                .lock("player.input")
                .handler(p -> {
                    requirePlayer();
                    KeyMapping km = findKey(p.string("key"));
                    int ticks = Math.max(1, Math.min(p.integer("ticks", 1), 20 * 60));
                    return new LongRunning() {
                        int left = ticks;
                        public Object tick() {
                            if (left-- > 0) { km.setDown(true); return null; }
                            km.setDown(false);
                            return Map.of("held", ticks);
                        }
                        public void cancel() { km.setDown(false); }
                    };
                }));
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

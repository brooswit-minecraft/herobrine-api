package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.Availability;
import dev.herobrine.api.core.ResourceDef;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Minimal dedicated-server resource set: enough to prove the server-side lifecycle actually runs
 * and exposes honest, server-appropriate state. Deliberately not an attempt at the client's full
 * resource inventory (world/entities/inventory/crafting/stations/Baritone are all client-only
 * concepts on this platform and are correctly absent here, not registered-then-broken).
 * References no {@code net.minecraft.client.*} class.
 */
final class ServerBindings {
    private ServerBindings() {}

    static void register(ApiContext ctx, MinecraftServer server) {
        ctx.observedTick = server::getTickCount;
        ctx.capability("server.players", () -> true);

        ctx.resources.register("server.info", new ResourceDef("server.info",
                "Dedicated server version, tick and player count", Availability.LOCAL, q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("minecraftVersion", SharedConstants.getCurrentVersion().getName());
                    m.put("motd", server.getMotd());
                    m.put("playerCount", server.getPlayerCount());
                    m.put("maxPlayers", server.getMaxPlayers());
                    m.put("tickCount", server.getTickCount());
                    return m;
                }));

        ctx.resources.register("server.players", new ResourceDef("server.players",
                "Names and UUIDs of currently connected players", Availability.LOCAL, q ->
                        server.getPlayerList().getPlayers().stream()
                                .map(ServerBindings::playerSummary)
                                .toList()));
    }

    private static Map<String, Object> playerSummary(ServerPlayer p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getGameProfile().getName());
        m.put("uuid", p.getUUID().toString());
        return m;
    }
}

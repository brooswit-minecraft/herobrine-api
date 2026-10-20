package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * All dedicated-server-only wiring. References no {@code net.minecraft.client.*} class, directly or
 * transitively, so it is safe to load on a dedicated server -- see {@link HerobrineApiMod}'s
 * constructor, which registers these listeners only when
 * {@code !FMLEnvironment.dist.isClient()} (the mod is loaded, but running as a dedicated server, not
 * as a client's embedded integrated server, which keeps this independent of the client's own API).
 */
final class ServerLifecycle {
    private static final Logger LOG = LogUtils.getLogger();

    private static volatile ApiController controller;

    private ServerLifecycle() {}

    /** Registered on {@code NeoForge.EVENT_BUS} from the mod constructor, dedicated-server dist only. */
    static void register(IEventBus neoForgeBus) {
        neoForgeBus.addListener(ServerLifecycle::onServerStarted);
        neoForgeBus.addListener(ServerLifecycle::onServerStopping);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ApiContext ctx = new ApiContext(new DedicatedServerGameThread(server));
        ctx.platform = "neoforge-1.21.1-dedicated-server";
        ctx.client = false; // honest capability reporting: no client-only resources/actions here
        ctx.log = msg -> LOG.info("[api] {}", msg);
        // The server itself is the authority; nothing here needs a control-block opt-in the way the
        // client does for remote multiplayer.
        ctx.controlBlock = () -> null;
        ServerBindings.register(ctx, server);

        ApiController c = new ApiController(ctx,
                ApiController.serverTokens(FMLPaths.CONFIGDIR.get()),
                ServerConfig.PORT::get,
                () -> {}); // nothing client-only to release on this platform
        controller = c;
        if (ServerConfig.ENABLED.get()) {
            try { c.start(); }
            catch (Exception e) { LOG.error("Failed to start Herobrine API (dedicated server)", e); }
        } else {
            LOG.info("Herobrine API is disabled in config (dedicated server)");
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ApiController c = controller;
        if (c != null) c.stop();
        controller = null;
    }
}

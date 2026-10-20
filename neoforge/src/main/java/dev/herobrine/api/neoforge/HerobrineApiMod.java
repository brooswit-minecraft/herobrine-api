package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Dist-agnostic entry point: NeoForge loads this on both the client and a dedicated server. It
 * only ever constructs/wires client-only or server-only state behind an explicit
 * {@code FMLEnvironment.dist.isClient()} check, so {@link ClientLifecycle} (and everything it
 * reaches: {@code ClientBindings}, {@code ModBindings}, {@code WorldBindings}, {@code
 * EntityBindings}, {@code InventoryBindings}, {@code CraftingBindings}, {@code StationBindings},
 * {@code ChatLog}, {@code BaritoneBindings}, {@code MinecraftGameThread}, {@code Game}) is never
 * even classloaded on a dedicated server. The client's own context, lifecycle and command remain
 * exactly as before; see {@link ClientLifecycle} and {@link ServerLifecycle} for the two halves.
 */
@Mod(HerobrineApiMod.MOD_ID)
public final class HerobrineApiMod {
    public static final String MOD_ID = "herobrine_api";
    private static final Logger LOG = LogUtils.getLogger();

    public HerobrineApiMod(IEventBus modBus, ModContainer container) {
        boolean isClient = FMLEnvironment.dist.isClient();

        if (isClient) {
            container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        }
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        // Capture the real on-disk path NeoForge resolves for ServerConfig's TOML the moment it
        // loads (once per world start), rather than guessing it from FMLPaths ourselves.
        modBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) {
                ServerConfig.configFilePath = event.getConfig().getFullPath();
            }
        });

        if (isClient) {
            ApiContext c = new ApiContext(new MinecraftGameThread());
            c.platform = "neoforge-1.21.1";
            c.controlBlock = ClientLifecycle::controlBlockReason;
            c.log = msg -> LOG.info("[api] {}", msg);
            ApiController controller = new ApiController(c,
                    ApiController.clientTokens(FMLPaths.CONFIGDIR.get()),
                    ClientConfig.PORT::get,
                    ClientBindings::releaseAllKeys);
            ClientLifecycle.init(c, controller);
            modBus.addListener(ClientLifecycle::onClientSetup);
        } else {
            // Dedicated server: ServerLifecycle builds its own ApiContext once a MinecraftServer
            // instance exists (ServerStartedEvent), since that's what DedicatedServerGameThread needs.
            ServerLifecycle.register(NeoForge.EVENT_BUS);
        }
    }
}

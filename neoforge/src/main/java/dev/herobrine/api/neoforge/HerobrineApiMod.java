package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiServer;
import dev.herobrine.api.core.Limits;
import dev.herobrine.api.core.TokenStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Client-only entry point. Registers built-in bindings, lets other mods register theirs via
 * {@link RegisterApiEvent}, then starts the loopback HTTP server.
 */
@Mod(value = HerobrineApiMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = HerobrineApiMod.MOD_ID, value = Dist.CLIENT)
public final class HerobrineApiMod {
    public static final String MOD_ID = "herobrine_api";
    private static final Logger LOG = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 25580;

    static volatile ApiContext ctx;

    public HerobrineApiMod(IEventBus modBus) {
        ApiContext c = new ApiContext(new MinecraftGameThread());
        c.platform = "neoforge-1.21.1";
        ctx = c;
        modBus.addListener(HerobrineApiMod::onClientSetup);
    }

    /** Runs after every mod constructor, so third-party RegisterApiEvent listeners are in place. */
    private static void onClientSetup(FMLClientSetupEvent event) {
        ApiContext c = ctx;
        ClientBindings.register(c);
        ModBindings.register(c);
        BaritoneBindings.register(c);
        NeoForge.EVENT_BUS.post(new RegisterApiEvent(c));
        try {
            TokenStore tokens = new TokenStore();
            String secret = tokens.create("local-admin", Set.of("*")).secret();
            Path file = FMLPaths.CONFIGDIR.get().resolve("herobrine_api.token");
            Files.writeString(file, secret);
            c.audit.sink(e -> LOG.info("[audit] {} {} {}", e.owner(), e.kind(), e.detail()));
            ApiServer server = new ApiServer(c, tokens, Limits.defaults());
            int bound = server.start(Integer.getInteger("herobrine.api.port", DEFAULT_PORT));
            LOG.info("Herobrine API on http://127.0.0.1:{}/api/v1 (admin token in {})", bound, file);
        } catch (Exception e) {
            LOG.error("Failed to start Herobrine API", e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ApiContext c = ctx;
        if (c != null) c.operations.tick();
    }
}

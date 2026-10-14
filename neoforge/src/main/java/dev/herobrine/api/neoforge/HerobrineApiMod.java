package dev.herobrine.api.neoforge;

import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Client-only entry point. Starts the loopback HTTP server and wires the platform
 * bindings (resources, actions, mods) into the platform-independent core.
 */
@Mod(value = HerobrineApiMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = HerobrineApiMod.MOD_ID, value = Dist.CLIENT)
public final class HerobrineApiMod {
    public static final String MOD_ID = "herobrine_api";
    private static final Logger LOG = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 25580;

    static ApiContext ctx;

    public HerobrineApiMod() {
        ctx = new ApiContext(new MinecraftGameThread());
        ctx.platform = "neoforge-1.21.1";
        ClientBindings.register(ctx);
        try {
            String token = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
            Path file = FMLPaths.CONFIGDIR.get().resolve("herobrine_api.token");
            Files.writeString(file, token);
            ApiServer server = new ApiServer(ctx, token);
            int port = Integer.getInteger("herobrine.api.port", DEFAULT_PORT);
            int bound = server.start(port);
            LOG.info("Herobrine API listening on http://127.0.0.1:{}/api/v1 (bearer token in {})", bound, file);
        } catch (Exception e) {
            LOG.error("Failed to start Herobrine API", e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ctx != null && Minecraft.getInstance() != null) ctx.operations.tick();
    }
}

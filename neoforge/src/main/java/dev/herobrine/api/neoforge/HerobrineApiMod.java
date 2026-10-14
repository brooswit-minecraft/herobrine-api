package dev.herobrine.api.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import dev.herobrine.api.core.ApiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Client-only entry point. Registers built-in bindings, lets other mods register theirs via
 * {@link RegisterApiEvent}, then starts the loopback HTTP server if enabled in the config.
 * Toggle at runtime with {@code /herobrine_api on|off|status}.
 */
@Mod(value = HerobrineApiMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = HerobrineApiMod.MOD_ID, value = Dist.CLIENT)
public final class HerobrineApiMod {
    public static final String MOD_ID = "herobrine_api";
    private static final Logger LOG = LogUtils.getLogger();

    static volatile ApiContext ctx;
    static volatile ApiController controller;

    public HerobrineApiMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        ApiContext c = new ApiContext(new MinecraftGameThread());
        c.platform = "neoforge-1.21.1";
        c.controlBlock = HerobrineApiMod::controlBlockReason;
        ctx = c;
        controller = new ApiController(c);
        modBus.addListener(HerobrineApiMod::onClientSetup);
    }

    /** Runs after every mod constructor, so third-party RegisterApiEvent listeners are in place. */
    private static void onClientSetup(FMLClientSetupEvent event) {
        ApiContext c = ctx;
        ClientBindings.register(c);
        ModBindings.register(c);
        BaritoneBindings.register(c);
        NeoForge.EVENT_BUS.post(new RegisterApiEvent(c));
        if (ClientConfig.ENABLED.get()) {
            try { controller.start(); }
            catch (Exception e) { LOG.error("Failed to start Herobrine API", e); }
        } else {
            LOG.info("Herobrine API is disabled in config; use /herobrine_api on to start it");
        }
    }

    /** Non-null while control actions must be refused: connected to a remote server without opt-in. */
    private static String controlBlockReason() {
        Minecraft mc = Minecraft.getInstance();
        boolean onRemoteServer = mc.level != null && !mc.hasSingleplayerServer();
        if (onRemoteServer && !ClientConfig.ALLOW_CONTROL_ON_MULTIPLAYER.get())
            return "Control actions are disabled on multiplayer servers "
                    + "(set allowControlOnMultiplayer=true in herobrine_api-client.toml to override)";
        return null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ApiContext c = ctx;
        if (c != null) c.operations.tick();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("herobrine_api")
                .then(Commands.literal("on").executes(cx -> {
                    ClientConfig.ENABLED.set(true);
                    ClientConfig.ENABLED.save();
                    try {
                        int port = controller.start();
                        reply(cx.getSource(), "Herobrine API running on 127.0.0.1:" + port);
                    } catch (Exception e) {
                        reply(cx.getSource(), "Could not start Herobrine API: " + e.getMessage());
                    }
                    return 1;
                }))
                .then(Commands.literal("off").executes(cx -> {
                    ClientConfig.ENABLED.set(false);
                    ClientConfig.ENABLED.save();
                    controller.stop();
                    reply(cx.getSource(), "Herobrine API stopped; running operations cancelled");
                    return 1;
                }))
                .then(Commands.literal("status").executes(cx -> {
                    reply(cx.getSource(), controller.isRunning()
                            ? "Herobrine API running on 127.0.0.1:" + controller.port()
                            : "Herobrine API is off");
                    return 1;
                })));
    }

    private static void reply(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}

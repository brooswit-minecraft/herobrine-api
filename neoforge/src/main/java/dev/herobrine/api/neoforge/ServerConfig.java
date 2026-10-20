package dev.herobrine.api.neoforge;

import java.nio.file.Path;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Dedicated-server config (per-world {@code serverconfig/herobrine_api-server.toml}). Mirrors
 * {@link ClientConfig}'s naming where the same concept applies; there is no
 * {@code allowControlOnMultiplayer} here because that concept (refusing control actions while a
 * *client* is connected to someone else's server) does not apply to the server itself.
 *
 * <p>The operator key lives in THIS SAME file, as the {@link #API_KEY} field -- not a separate
 * file -- per the ticket's "simple API key stored persistently in its config file" requirement.
 * It starts blank; {@code ServerApiKeyAccessor} (adapting it to {@code Tokens.SecretAccessor})
 * fills it in with a fresh value on first access and regenerates it in place if it is ever found
 * blank again, using the exact same regenerate-on-access/stability/concurrency contract as the
 * client's file-based tokens.
 */
final class ServerConfig {
    private ServerConfig() {}

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.IntValue PORT;
    static final ModConfigSpec.IntValue MAX_EDIT_VOLUME;
    static final ModConfigSpec.IntValue MAX_SCAN_VOLUME;
    static final ModConfigSpec.ConfigValue<String> API_KEY;

    /**
     * The real on-disk path of this config's TOML file, captured from
     * {@code ModConfigEvent.Loading} the moment NeoForge loads it (see {@link HerobrineApiMod}) --
     * never guessed. Null until that event fires, which happens once a world/server actually
     * starts (this is a per-world, {@code ModConfig.Type.SERVER} config).
     */
    static volatile Path configFilePath;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Run the HTTP API on this dedicated server. The operator key is the",
                        "apiKey field below, in this same file.")
                .define("enabled", true);
        PORT = b.comment("Loopback port for the HTTP API (127.0.0.1 only).")
                .defineInRange("port", 25581, 1024, 65535);
        MAX_EDIT_VOLUME = b.comment("Largest box (in blocks) that bulk-edit actions will accept.")
                .defineInRange("maxEditVolume", 100_000, 1, 10_000_000);
        MAX_SCAN_VOLUME = b.comment("Largest box (in blocks) that bulk-scan resources will scan in one request.")
                .defineInRange("maxScanVolume", 32_768, 1, 1_000_000);
        API_KEY = b.comment("The dedicated server's bearer key (full access; there is no separate",
                        "read-only key server-side). Left blank here; a fresh one is generated and",
                        "written back into this field the first time the API starts, and again if",
                        "this field is ever found blank. Treat it like a password.")
                .define("apiKey", "");
        SPEC = b.build();
    }
}

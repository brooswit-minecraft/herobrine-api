package dev.herobrine.api.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Dedicated-server config (per-world {@code serverconfig/herobrine_api-server.toml}). Mirrors
 * {@link ClientConfig}'s naming where the same concept applies; there is no
 * {@code allowControlOnMultiplayer} here because that concept (refusing control actions while a
 * *client* is connected to someone else's server) does not apply to the server itself.
 */
final class ServerConfig {
    private ServerConfig() {}

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.IntValue PORT;
    static final ModConfigSpec.IntValue MAX_EDIT_VOLUME;
    static final ModConfigSpec.IntValue MAX_SCAN_VOLUME;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Run the HTTP API on this dedicated server. The operator key lives in",
                        "config/" + ApiController.SERVER_TOKEN_FILE + " next to this config file.")
                .define("enabled", true);
        PORT = b.comment("Loopback port for the HTTP API (127.0.0.1 only).")
                .defineInRange("port", 25581, 1024, 65535);
        MAX_EDIT_VOLUME = b.comment("Largest box (in blocks) that bulk-edit actions will accept.")
                .defineInRange("maxEditVolume", 100_000, 1, 10_000_000);
        MAX_SCAN_VOLUME = b.comment("Largest box (in blocks) that bulk-scan resources will scan in one request.")
                .defineInRange("maxScanVolume", 32_768, 1, 1_000_000);
        SPEC = b.build();
    }
}

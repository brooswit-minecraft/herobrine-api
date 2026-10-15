package dev.herobrine.api.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side config (config/herobrine_api-client.toml). */
final class ClientConfig {
    private ClientConfig() {}

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.IntValue PORT;
    static final ModConfigSpec.BooleanValue ALLOW_CONTROL_ON_MULTIPLAYER;
    static final ModConfigSpec.IntValue MAX_EDIT_VOLUME;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Run the HTTP API. Toggle in-game with /herobrine_api on|off; rotate tokens with /herobrine_api regenerate.")
                .define("enabled", true);
        PORT = b.comment("Loopback port for the HTTP API (127.0.0.1 only).")
                .defineInRange("port", 25580, 1024, 65535);
        ALLOW_CONTROL_ON_MULTIPLAYER = b.comment(
                        "If false (default), control actions (input, chat, movement, Baritone...) are refused while",
                        "connected to a multiplayer server. Read-only endpoints keep working. Many servers ban automation;",
                        "only enable this where it is explicitly allowed.")
                .define("allowControlOnMultiplayer", false);
        MAX_EDIT_VOLUME = b.comment("Largest box (in blocks) that baritone.fill / clear_area / replace will accept.")
                .defineInRange("maxEditVolume", 100_000, 1, 10_000_000);
        SPEC = b.build();
    }
}

package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.Tokens;
import java.nio.file.Path;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Adapts {@link ServerConfig#API_KEY} -- a plain {@code String} field living in the SAME TOML file
 * as {@code enabled}/{@code port}/etc ({@code serverconfig/herobrine_api-server.toml}) -- to the
 * {@link Tokens.SecretAccessor} contract, so {@link Tokens#loadOrCreateSingle(Tokens.SecretAccessor)}
 * gives the dedicated server's operator key the exact same regenerate-on-access, stability, and
 * concurrency guarantees as the client's file-based tokens, without a second file and without a
 * new auth model: reads/writes go through {@code ModConfigSpec}'s own supported API.
 * <p>
 * {@link #read()} calls {@link ModConfigSpec.ConfigValue#getRaw()} rather than {@code get()} so it
 * always reflects the live loaded config, never a value this accessor cached itself.
 * {@link #write(String)} calls {@code set} then {@code save()}, which persists the whole TOML file
 * back out through NightConfig's own file-backed {@code Config} (the same mechanism NeoForge uses
 * for every config write) and fires the config-reloading event; verified end-to-end with a real
 * disposable dedicated-server run (see docs/SERVER.md). Known limitation: unlike the client's
 * per-request disk re-read, this does not notice an operator hand-editing the TOML file on disk
 * while the server is running -- NeoForge does not live-watch {@code ModConfig.Type.SERVER} files
 * for external edits, so such an edit only takes effect on the next server start.
 */
final class ServerApiKeyAccessor implements Tokens.SecretAccessor {
    private static final int MIN_SECRET_LENGTH = 32;

    private final ModConfigSpec.ConfigValue<String> value;
    private final Path configFilePath;

    /** @param configFilePath the real on-disk path of the TOML file backing {@code value},
     *                        captured from {@code ModConfigEvent.Loading} -- never guessed. */
    ServerApiKeyAccessor(ModConfigSpec.ConfigValue<String> value, Path configFilePath) {
        this.value = value;
        this.configFilePath = configFilePath;
    }

    @Override
    public String read() {
        String raw = value.getRaw();
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.length() >= MIN_SECRET_LENGTH ? trimmed : null;
    }

    @Override
    public void write(String secret) {
        value.set(secret);
        value.save();
    }

    @Override
    public Path lockAnchor() { return configFilePath; }
}

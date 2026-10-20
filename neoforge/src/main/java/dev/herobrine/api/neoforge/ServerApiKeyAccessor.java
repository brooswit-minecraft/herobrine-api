package dev.herobrine.api.neoforge;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Adapts {@link ServerConfig#API_KEY} -- a plain {@code String} field living in the SAME TOML file
 * as {@code enabled}/{@code port}/etc ({@code serverconfig/herobrine_api-server.toml}) -- to the
 * {@link Tokens.SecretAccessor} contract, so {@link Tokens#loadOrCreateSingle(Tokens.SecretAccessor)}
 * gives the dedicated server's operator key the exact same regenerate-on-access, stability, and
 * concurrency guarantees as the client's file-based tokens, without a second file and without a
 * new auth model.
 * <p>
 * <b>Why this reads the file directly instead of {@code ModConfigSpec.ConfigValue#getRaw()}:</b>
 * {@code getRaw()} reads {@code spec.loadedConfig.config()}, an in-memory {@code Config} object
 * NeoForge populated once when the config loaded -- it is never re-read from disk afterward.
 * NeoForge does not live-watch {@code ModConfig.Type.SERVER} files for external changes, so if an
 * operator deletes the file or blanks the {@code apiKey} key while the server is running, {@code
 * getRaw()} keeps returning the stale in-memory value forever. So {@link #read()} re-parses
 * {@link #configFilePath} as real TOML on every call, every time.
 * <p>
 * <b>Why a real TOML parser, not line-based text matching:</b> an earlier version of this class
 * found and replaced the {@code apiKey} entry with a regex over raw lines. That broke the moment a
 * hand-edited line didn't match the exact expected shape -- e.g. an operator adding an inline
 * comment ({@code apiKey = "..." # rotated manually}) made the regex miss the existing line
 * entirely, so a "regenerate" appended a SECOND {@code apiKey} entry instead of replacing the
 * first, producing a TOML file with a duplicate key (invalid TOML; NeoForge's own parser could
 * throw on it the next time the config truly loads). Using NightConfig's own {@link TomlParser} /
 * {@link TomlFormat}'s writer -- the exact library {@code ModConfigSpec} itself is backed by --
 * means the file is parsed into a structured {@link CommentedConfig}, {@code apiKey} is set as an
 * ordinary top-level key on that object, and the whole thing is re-serialized by the same writer
 * NeoForge's own config system uses. Every other key and its comments survive because that is
 * what a structured config object -- not raw lines -- naturally preserves; comment placement and
 * key order come along for free via {@link CommentedConfig}'s own comment map and (for order)
 * NightConfig's insertion-ordered backing map.
 * <p>
 * Fails closed on malformed/ambiguous input: a file that isn't valid TOML, or an {@code apiKey}
 * entry that exists but isn't a string, makes {@link #read()}/{@link #write} throw rather than
 * guess, duplicate, or silently drop data -- the same fail-closed contract {@code Tokens} already
 * applies to a plain persistence failure.
 * <p>
 * Persistence itself still goes through {@link Tokens#writeOwnerOnlyAtomic} (write-temp-with-
 * owner-only-permissions, then atomic rename) -- the serialized TOML text is handed to it like any
 * other content, so the crash-safety and restrictive-permission guarantees are unchanged. After a
 * successful write, {@link ModConfigSpec.ConfigValue#set} is also called (best-effort) purely so
 * NeoForge's own in-memory cache doesn't silently diverge from disk for any other in-process reader
 * -- {@code save()} is deliberately never called here, since that would let NeoForge rewrite the
 * whole file from its in-memory model instead of our own structured-but-still-ours edit.
 */
final class ServerApiKeyAccessor implements Tokens.SecretAccessor {
    private static final int MIN_SECRET_LENGTH = 32;
    private static final String KEY_NAME = "apiKey";

    private final ModConfigSpec.ConfigValue<String> value;
    private final Path configFilePath;

    /** @param configFilePath the real on-disk path of the TOML file backing {@code value},
     *                        captured from {@code ModConfigEvent.Loading} -- never guessed. */
    ServerApiKeyAccessor(ModConfigSpec.ConfigValue<String> value, Path configFilePath) {
        this.value = value;
        this.configFilePath = configFilePath;
    }

    @Override
    public String read() throws IOException {
        if (!Files.isRegularFile(configFilePath)) return null;
        CommentedConfig cfg = parse(configFilePath);
        Object raw = cfg.get(KEY_NAME);
        if (raw == null) return null; // key absent entirely: treat the same as blank
        if (!(raw instanceof String s)) {
            // Structurally ambiguous: never guess what a non-string apiKey entry means.
            throw new IOException("the dedicated server's '" + KEY_NAME + "' config entry is not a string in " + configFilePath);
        }
        String trimmed = s.trim();
        return trimmed.length() >= MIN_SECRET_LENGTH ? trimmed : null;
    }

    @Override
    public void write(String secret) throws IOException {
        CommentedConfig cfg = Files.isRegularFile(configFilePath) ? parse(configFilePath) : TomlFormat.newConfig();
        cfg.set(KEY_NAME, secret);
        String serialized = TomlFormat.instance().createWriter().writeToString(cfg);

        // Owner-only, write-temp-then-atomic-rename -- see Tokens#writeOwnerOnlyAtomic's own
        // javadoc for why this is what keeps the WHOLE file restricted, including on the very
        // first regenerate (a brand-new file, not just one that already existed restricted).
        Tokens.writeOwnerOnlyAtomic(configFilePath, List.of(serialized));

        // Best-effort only: the file write above is the durable, authoritative result already.
        // This just keeps NeoForge's in-memory cache from silently disagreeing with disk for any
        // other in-process reader of ServerConfig.API_KEY -- never save() (see class javadoc).
        try {
            value.set(secret);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public Path lockAnchor() { return configFilePath; }

    /** Parses {@code path} as TOML, failing closed (a clear, path-only, non-secret-leaking
     * message) rather than guessing anything about malformed content. */
    private static CommentedConfig parse(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new TomlParser().parse(r);
        } catch (ParsingException e) {
            throw new IOException("the dedicated server's config file is not valid TOML: " + path, e);
        }
    }
}

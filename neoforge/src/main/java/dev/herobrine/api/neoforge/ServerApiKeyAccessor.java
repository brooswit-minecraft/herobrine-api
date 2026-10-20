package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * operator deletes the file or blanks the {@code apiKey} line while the server is running, {@code
 * getRaw()} keeps returning the stale in-memory value forever: the "regenerate on next access, no
 * restart" requirement would silently not apply to this flavor. So {@link #read()} parses the real
 * {@code apiKey} line out of {@link #configFilePath} itself, every time, and {@link #write(String)}
 * replaces that one line via write-temp-then-atomic-rename (same technique {@code Tokens}'s own
 * file-based accessor uses) -- every other line (comments, {@code enabled}, {@code port}, ...) is
 * carried through byte-for-byte. After a successful write, {@link ModConfigSpec.ConfigValue#set}
 * is also called (best-effort) purely so NeoForge's own in-memory cache doesn't silently diverge
 * from disk for any other in-process reader -- {@code save()} is deliberately never called here,
 * since that would let NeoForge rewrite the whole file from its in-memory model instead of our
 * direct, minimal, byte-for-byte-preserving edit.
 */
final class ServerApiKeyAccessor implements Tokens.SecretAccessor {
    private static final int MIN_SECRET_LENGTH = 32;
    /** Matches a NightConfig-TOML-serialized {@code apiKey = "..."} line; {@code group(1)} is the
     * raw (unescaped) value. NightConfig always quotes string values, including the blank default. */
    private static final Pattern API_KEY_LINE = Pattern.compile("^\\s*apiKey\\s*=\\s*\"(.*)\"\\s*$");
    private static final String KEY_NAME = "apiKey";
    private static final String TMP_SUFFIX = ".tmp-";

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
        for (String line : Files.readAllLines(configFilePath, StandardCharsets.UTF_8)) {
            Matcher m = API_KEY_LINE.matcher(line);
            if (m.matches()) {
                String v = m.group(1).trim();
                return v.length() >= MIN_SECRET_LENGTH ? v : null;
            }
        }
        return null; // no apiKey line at all: treat the same as blank
    }

    @Override
    public void write(String secret) throws IOException {
        List<String> existing = Files.isRegularFile(configFilePath)
                ? Files.readAllLines(configFilePath, StandardCharsets.UTF_8)
                : List.of();
        List<String> out = new ArrayList<>(existing.size() + 1);
        boolean replaced = false;
        for (String line : existing) {
            if (API_KEY_LINE.matcher(line).matches()) {
                out.add(KEY_NAME + " = \"" + secret + "\"");
                replaced = true;
            } else {
                out.add(line);
            }
        }
        if (!replaced) out.add(KEY_NAME + " = \"" + secret + "\"");

        Path tmp = configFilePath.resolveSibling(configFilePath.getFileName().toString() + TMP_SUFFIX + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        try {
            Files.write(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, configFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("could not persist the dedicated server's API key to " + configFilePath, e);
        }

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
}

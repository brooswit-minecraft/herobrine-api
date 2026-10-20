package dev.herobrine.api.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the REAL {@link ServerApiKeyAccessor} directly -- not a fake -- against real temp
 * files, covering the full fixture matrix the ticket requires for the dedicated server's key,
 * including the real-TOML-parser regression this class now uses (an earlier regex-based version
 * broke on hand-edited lines it didn't exactly expect, e.g. a trailing inline comment, by
 * appending a duplicate {@code apiKey} entry instead of replacing the existing one).
 * <p>
 * {@code ServerApiKeyAccessor}'s {@link ServerApiKeyAccessor#read} and {@link
 * ServerApiKeyAccessor#write} never touch {@code ModConfigSpec.ConfigValue} except for a
 * best-effort {@code set()} inside {@code write()} that swallows any {@code RuntimeException} --
 * neither method depends on NeoForge's config-loading lifecycle (the thing that is genuinely
 * blocked outside a running FML environment: {@code IConfigSpec.ILoadedConfig} is sealed to a
 * package-private class whose own construction needs a package-private {@code ModConfig} plus a
 * {@code ModContainer}/{@code IModInfo}). So this test builds a real {@code ModConfigSpec} and a
 * real {@code ConfigValue<String>} the exact same way {@link ServerConfig}'s own static
 * initializer does -- plain {@code ModConfigSpec.Builder} calls, no FML runtime needed for that
 * part -- and constructs a real {@code ServerApiKeyAccessor} with it and a real temp file, with no
 * mod environment, no {@code ModConfigEvent}, no {@code acceptConfig}. The spec is deliberately
 * left "unloaded" (its {@code loadedConfig} stays null), which is exactly what proves {@code
 * value.set()} inside {@code write()} is truly best-effort: see {@link
 * #writeToleratesAnUnloadedConfigSpecWithoutFailing} for direct confirmation of what it actually
 * throws there and that {@code write()} swallows it cleanly.
 */
class ServerApiKeyAccessorTest {
    @TempDir Path tmp;

    /** Builds a real, independent {@code apiKey} ConfigValue -- not {@code ServerConfig.API_KEY}
     * (a shared static singleton) -- so tests can't interfere with each other. Never loaded/
     * accepted by FML, matching how little of the lifecycle write()'s best-effort set() needs. */
    private static ModConfigSpec.ConfigValue<String> freshApiKeyValue() {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ModConfigSpec.ConfigValue<String> apiKey = b.comment("test fixture").define("apiKey", "");
        b.build(); // wires apiKey.spec; the returned ModConfigSpec itself is otherwise unused here
        return apiKey;
    }

    /** Reads the {@code apiKey} entry via a real TOML parse -- the same library the accessor
     * itself uses -- rather than text matching, so this test oracle doesn't share (or mask) any
     * bug the old regex-based approach could have had. */
    private static String readField(Path f) throws IOException {
        CommentedConfig cfg = parseReal(f);
        Object raw = cfg.get("apiKey");
        if (raw == null) throw new IllegalStateException("no apiKey entry in " + f);
        return (String) raw;
    }

    private static CommentedConfig parseReal(Path f) throws IOException {
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            return new TomlParser().parse(r);
        }
    }

    /** Counts textual occurrences of an {@code apiKey} key=value line, independent of parsing --
     * this is exactly what would show TWO entries if a regenerate ever appended instead of
     * replaced (invalid TOML; a real parser would only ever see the LAST one via its map, hiding
     * the duplication from a parse-based check -- so this has to look at the raw text). */
    private static long countApiKeyLinesInRawText(Path f) throws IOException {
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                .filter(l -> l.trim().startsWith("apiKey") || l.trim().matches("apiKey\\s*=.*"))
                .count();
    }

    @Test void writeToleratesAnUnloadedConfigSpecWithoutFailing() throws IOException {
        // Direct confirmation of what ConfigValue#set() actually throws against an unloaded spec,
        // and that it is exactly the RuntimeException subtype write()'s catch clause already
        // handles (Guava's Preconditions.checkNotNull -> NullPointerException, a RuntimeException).
        ModConfigSpec.ConfigValue<String> value = freshApiKeyValue();
        assertThrows(NullPointerException.class, () -> value.set("anything"),
                "sanity: set() on an unloaded spec throws NPE, which write()'s catch(RuntimeException) already covers");

        // And write() itself must not propagate it.
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(value, tmp.resolve("x.toml"));
        assertDoesNotThrow(() -> accessor.write("f".repeat(48)));
    }

    @Test void missingFileIsTreatedAsBlankAndRegeneratesOnNextAccess() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        assertFalse(Files.exists(f));
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertNull(accessor.read(), "no file yet: read() must report blank, not throw");
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        assertTrue(Files.isRegularFile(f), "a key must be generated and persisted with no file present");
        String generated = readField(f);
        assertTrue(generated.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(generated).orElseThrow());
    }

    @Test void deletedFileRegeneratesOnNextAccessWithoutRestart() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String original = readField(f);
        assertEquals(Tokens.Access.FULL, t.authenticate(original).orElseThrow());

        Files.delete(f); // same JVM, same accessor instance, no restart
        assertTrue(t.authenticate(original).isEmpty(), "the old key must stop working once its file is gone");
        assertTrue(Files.isRegularFile(f), "a missing file is regenerated on next access, no restart");
        String regenerated = readField(f);
        assertNotEquals(original, regenerated);
        assertEquals(Tokens.Access.FULL, t.authenticate(regenerated).orElseThrow());
    }

    @Test void emptyValueRegeneratesOnNextAccess() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        Files.write(f, List.of("enabled = true", "apiKey = \"\""), StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertNull(accessor.read());
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String generated = readField(f);
        assertTrue(generated.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(generated).orElseThrow());
    }

    @Test void whitespaceOnlyValueRegeneratesOnNextAccess() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        Files.write(f, List.of("enabled = true", "apiKey = \"   \t  \""), StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertNull(accessor.read(), "whitespace-only, after trim, must be treated as blank");
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String generated = readField(f);
        assertTrue(generated.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(generated).orElseThrow());
    }

    @Test void existingValidValueStaysStableAndIsNotRewritten() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String original = readField(f);
        var mtimeBefore = Files.getLastModifiedTime(f);

        for (int i = 0; i < 5; i++) t.authenticate(original);

        assertEquals(original, readField(f), "a valid value must never be spuriously rewritten");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(f), "no rewrite at all should have happened");
    }

    @Test void persistedValueIsWhatBecomesActive() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        accessor.write("a".repeat(48));
        String onDisk = readField(f);
        assertEquals("a".repeat(48), onDisk);

        Tokens t = Tokens.loadOrCreateSingle(accessor);
        assertEquals(Tokens.Access.FULL, t.authenticate(onDisk).orElseThrow());
    }

    @Test void oldKeyRejectedAfterRotate() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String original = readField(f);
        assertEquals(Tokens.Access.FULL, t.authenticate(original).orElseThrow());

        t.regenerate();
        assertTrue(t.authenticate(original).isEmpty(), "the old key's hash must stop authenticating immediately");
        String rotated = readField(f);
        assertNotEquals(original, rotated);
        assertEquals(Tokens.Access.FULL, t.authenticate(rotated).orElseThrow());
    }

    @Test void persistenceFailureFailsClosedAndAcceptsNoNewKey() throws IOException {
        Path dir = tmp.resolve("locked-dir");
        Files.createDirectory(dir);
        Path f = dir.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor); // succeeds while the dir is still writable
        String original = readField(f);

        var perms = Files.getPosixFilePermissions(dir);
        try {
            // Overwriting the EXISTING file's bytes only needs write permission on the file
            // itself; locking the directory blocks write()'s temp-file-create-then-rename instead.
            Files.write(f, List.of("enabled = true", "apiKey = \"\""), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            return; // POSIX permissions unsupported on this filesystem; nothing to assert here.
        }

        try {
            Tokens.TokensUnavailableException ex = assertThrows(Tokens.TokensUnavailableException.class,
                    () -> t.authenticate("probe-value-that-must-not-leak"),
                    "a regenerate that cannot persist must fail closed, not hand out an unsaved key");
            assertFalse(ex.getMessage().contains("probe-value-that-must-not-leak"));
            assertFalse(ex.getMessage().contains(original));
        } finally {
            Files.setPosixFilePermissions(dir, perms);
        }
    }

    @Test void unrelatedEntriesAndCommentsSurviveARegenerate() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        List<String> seed = List.of(
                "#Run the HTTP API on this dedicated server.",
                "enabled = true",
                "#Loopback port for the HTTP API (127.0.0.1 only).",
                "port = 25581",
                "maxEditVolume = 100000",
                "maxScanVolume = 32768",
                "#The dedicated server's bearer key.",
                "apiKey = \"\"");
        Files.write(f, seed, StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        Tokens t = Tokens.loadOrCreateSingle(accessor);
        t.regenerate(); // a second write, to prove this holds across repeated regenerates too

        CommentedConfig after = parseReal(f);
        assertEquals(Boolean.TRUE, after.get("enabled"));
        assertEquals(25581, (Object) after.<Integer>get("port"));
        assertEquals(100000, (Object) after.<Integer>get("maxEditVolume"));
        assertEquals(32768, (Object) after.<Integer>get("maxScanVolume"));
        // Comments are preserved per-key by the structured round trip (that's the whole point of
        // CommentedConfig), even though the exact text formatting is the writer's own, not ours.
        assertTrue(after.getComment("enabled") != null && after.getComment("enabled").contains("HTTP API"));
        assertTrue(after.getComment("port") != null && after.getComment("port").contains("Loopback"));
        assertTrue(readField(f).length() >= 32);
    }

    @Test void filePermissionsStayOwnerOnlyAcrossARegenerateThroughTheRealAccessor() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor); // first-ever write: brand-new file

        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                    "the very first write through the real accessor must already be owner-only");
        } catch (UnsupportedOperationException e) {
            return; // POSIX permissions unsupported on this filesystem; nothing to assert here.
        }

        t.regenerate(); // a second write, through the real accessor again
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                "a regenerate through the real accessor must never widen the file's permissions");
    }

    @Test void noSecretAppearsInExceptionMessagesFromTheRealAccessor() throws IOException {
        Path dir = tmp.resolve("locked-dir-2");
        Files.createDirectory(dir);
        Path f = dir.resolve("herobrine_api-server.toml");
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor);
        String original = readField(f);

        var perms = Files.getPosixFilePermissions(dir);
        try {
            Files.write(f, List.of("apiKey = \"\""), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            return;
        }
        try {
            Tokens.TokensUnavailableException ex = assertThrows(Tokens.TokensUnavailableException.class,
                    () -> t.authenticate("probe-value-that-must-not-leak-either"));
            String msg = ex.getMessage();
            assertNotNull(msg);
            assertFalse(msg.contains("probe-value-that-must-not-leak-either"));
            assertFalse(msg.contains(original));
        } finally {
            Files.setPosixFilePermissions(dir, perms);
        }
    }

    // ---- regression coverage for the regex -> real-TOML-parser fix ----

    @Test void validKeyLineWithATrailingInlineCommentIsReadAsTheExistingKeyNotMissing() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        String existing = "c".repeat(48);
        Files.write(f, List.of(
                "enabled = true",
                "apiKey = \"" + existing + "\" # rotated manually"), StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertEquals(existing, accessor.read(),
                "a trailing inline comment must not make a valid key look missing");

        Tokens t = Tokens.loadOrCreateSingle(accessor);
        var mtimeBefore = Files.getLastModifiedTime(f);
        assertEquals(Tokens.Access.FULL, t.authenticate(existing).orElseThrow());
        assertEquals(mtimeBefore, Files.getLastModifiedTime(f), "a valid, already-correct key must not trigger a rewrite");
        assertEquals(1, countApiKeyLinesInRawText(f));
    }

    @Test void regenerateNeverDuplicatesTheApiKeyEntryRegardlessOfOriginalFormatting() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        // The exact shape that broke the old regex: a trailing inline comment on the apiKey line.
        Files.write(f, List.of(
                "enabled = true",
                "apiKey = \"" + "d".repeat(48) + "\" # rotated manually"), StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);
        Tokens t = Tokens.loadOrCreateSingle(accessor);

        t.regenerate(); // forces a real rewrite regardless of the original value's validity

        assertEquals(1, countApiKeyLinesInRawText(f), "a regenerate must replace the existing entry, never append a second one");
        // And the file must still be valid, re-parseable TOML with exactly one apiKey key.
        CommentedConfig after = parseReal(f);
        assertNotNull(after.get("apiKey"));
        assertTrue(readField(f).length() >= 32);
    }

    @Test void roundTripsReorderedKeysBlankLinesAndAPrecedingComment() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        // Deliberately out of the order ServerConfig itself would write, with blank lines and a
        // comment directly above the apiKey line -- all plausible hand-edits.
        Files.write(f, List.of(
                "maxScanVolume = 32768",
                "",
                "port = 25581",
                "enabled = true",
                "",
                "# manually rotated below",
                "apiKey = \"" + "e".repeat(48) + "\""), StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertEquals("e".repeat(48), accessor.read());

        Tokens t = Tokens.loadOrCreateSingle(accessor);
        t.regenerate();

        CommentedConfig after = parseReal(f);
        assertEquals(32768, (Object) after.<Integer>get("maxScanVolume"));
        assertEquals(25581, (Object) after.<Integer>get("port"));
        assertEquals(Boolean.TRUE, after.get("enabled"));
        assertEquals(1, countApiKeyLinesInRawText(f));
        assertTrue(readField(f).length() >= 32);
    }

    @Test void malformedTomlFailsClosedWithoutCorruptingAnything() throws IOException {
        Path f = tmp.resolve("herobrine_api-server.toml");
        String malformed = "enabled = true\napiKey = \"unterminated-string\n"; // missing closing quote
        Files.writeString(f, malformed, StandardCharsets.UTF_8);
        ServerApiKeyAccessor accessor = new ServerApiKeyAccessor(freshApiKeyValue(), f);

        assertThrows(IOException.class, accessor::read, "genuinely malformed TOML must fail closed, not guess");
        assertThrows(IOException.class, () -> Tokens.loadOrCreateSingle(accessor));

        assertEquals(malformed, Files.readString(f, StandardCharsets.UTF_8), "a failed parse must never touch the file");
    }
}

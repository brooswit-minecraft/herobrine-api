package dev.herobrine.api.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import dev.herobrine.api.core.Tokens;
import java.io.IOException;
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
 * files, covering the full fixture matrix the ticket requires for the dedicated server's key.
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

    private static String readField(Path f) throws IOException {
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.trim().startsWith("apiKey")) {
                int i = line.indexOf('"'), j = line.lastIndexOf('"');
                return i >= 0 && j > i ? line.substring(i + 1, j) : "";
            }
        }
        throw new IllegalStateException("no apiKey line in " + f);
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

    @Test void unrelatedEntriesAndCommentsSurviveAWriteByteForByte() throws IOException {
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

        List<String> after = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals(seed.size(), after.size());
        for (int i = 0; i < seed.size() - 1; i++) { // every line except the apiKey one itself
            assertEquals(seed.get(i), after.get(i), "line " + i + " must survive byte-for-byte");
        }
        assertNotEquals(seed.get(seed.size() - 1), after.get(after.size() - 1));
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
}

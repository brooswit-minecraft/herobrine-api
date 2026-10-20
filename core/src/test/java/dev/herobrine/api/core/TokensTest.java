package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the "regenerate on next access, no restart" contract for both the client's two-file
 * FULL/READ split ({@link Tokens#loadOrCreate}) and the server's one-file key
 * ({@link Tokens#loadOrCreateSingle}). All secrets here are throwaway test fixtures written to a
 * JUnit {@code @TempDir}; nothing here is a real credential.
 */
class TokensTest {
    @TempDir Path tmp;

    // ---- single-key (server) ----

    @Test void singleKeyIsCreatedWhenMissing() throws IOException {
        Path f = tmp.resolve("server.token");
        assertFalse(Files.exists(f));
        Tokens t = Tokens.loadOrCreateSingle(f);
        assertTrue(Files.isRegularFile(f));
        String secret = Files.readString(f).trim();
        assertTrue(secret.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(secret).orElseThrow());
        assertTrue(t.authenticate("wrong").isEmpty());
    }

    @Test void singleKeyNeverGrantsRead() throws IOException {
        Tokens t = Tokens.loadOrCreateSingle(tmp.resolve("server.token"));
        String secret = Files.readString(tmp.resolve("server.token")).trim();
        assertEquals(Tokens.Access.FULL, t.authenticate(secret).orElseThrow());
    }

    @Test void singleKeyStableAcrossRepeatedLoads() throws IOException {
        Path f = tmp.resolve("server.token");
        Tokens t1 = Tokens.loadOrCreateSingle(f);
        String s1 = Files.readString(f).trim();
        // repeated access (authenticate re-checks the file) must not rewrite a valid value
        for (int i = 0; i < 5; i++) t1.authenticate(s1);
        assertEquals(s1, Files.readString(f).trim());

        Tokens t2 = Tokens.loadOrCreateSingle(f); // "restart": same file, same secret
        assertEquals(Tokens.Access.FULL, t2.authenticate(s1).orElseThrow());
    }

    @Test void singleKeyRegeneratesOnDeletionWithoutRestart() throws IOException {
        Path f = tmp.resolve("server.token");
        Tokens t = Tokens.loadOrCreateSingle(f);
        String original = Files.readString(f).trim();

        Files.delete(f);
        assertTrue(t.authenticate(original).isEmpty(), "old key must stop working once the file is gone");
        assertTrue(Files.isRegularFile(f), "a missing key is regenerated on next access, no restart");
        String regenerated = Files.readString(f).trim();
        assertNotEquals(original, regenerated);
        assertEquals(Tokens.Access.FULL, t.authenticate(regenerated).orElseThrow());
    }

    @Test void singleKeyRegeneratesOnEmptyOrBlankValue() throws IOException {
        Path f = tmp.resolve("server.token");
        Tokens t = Tokens.loadOrCreateSingle(f);

        Files.writeString(f, "");
        assertEquals(Tokens.Access.FULL, authenticateAfterRefresh(t, f));

        Files.writeString(f, "   \n\t  ");
        assertEquals(Tokens.Access.FULL, authenticateAfterRefresh(t, f));
    }

    @Test void singleKeyPersistedValueIsTheOneThatBecomesActive() throws IOException {
        Path f = tmp.resolve("server.token");
        Tokens t = Tokens.loadOrCreateSingle(f);
        Files.writeString(f, "");
        t.authenticate("anything"); // triggers the refresh/regenerate path
        String onDisk = Files.readString(f).trim();
        assertEquals(Tokens.Access.FULL, t.authenticate(onDisk).orElseThrow());
    }

    @Test void singleKeyPersistenceFailureOnInitialCreateFailsClosed() throws IOException {
        // The parent of the token file is a regular file, not a directory: any attempt to create or
        // atomically move a token file under it must fail, and must fail before anything is accepted.
        Path brokenParent = tmp.resolve("broken-parent");
        Files.writeString(brokenParent, "not a directory");
        Path broken = brokenParent.resolve("server.token");
        assertThrows(IOException.class, () -> Tokens.loadOrCreateSingle(broken));
        assertFalse(Files.exists(broken), "nothing should be left behind on a failed persist");
    }

    @Test void singleKeyPersistenceFailureOnLaterRegenerateFailsClosedAndRejectsEverything() throws IOException {
        Path dir = tmp.resolve("locked-dir");
        Files.createDirectory(dir);
        Path f = dir.resolve("server.token");
        Tokens t = Tokens.loadOrCreateSingle(f); // succeeds while the directory is still writable
        Files.delete(f);

        var original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            assertThrows(Tokens.TokensUnavailableException.class, () -> t.authenticate("anything"),
                    "a regenerate that cannot persist must fail closed, not hand out an unsaved key");
            assertFalse(Files.exists(f), "no unsaved key should appear on disk");
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unsupported on this filesystem; nothing to assert here.
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    @Test void singleKeyConcurrentAccessConvergesOnOneValue() throws Exception {
        Path f = tmp.resolve("server.token");
        assertConvergesOnOneValue(() -> {
            Tokens.loadOrCreateSingle(f); // simulates independent racing accessors
            return Files.readString(f).trim();
        });
    }

    /** Independent corroboration of the above with a higher thread count and several repeats,
     * since OverlappingFileLockException-style bugs are exactly the kind that can pass once by
     * luck and still be broken -- this is not meant to replace the test above, just make the
     * "not flaky" claim concrete. */
    @RepeatedTest(5) void singleKeyConcurrentAccessConvergesOnOneValueRepeatedly() throws Exception {
        Path f = tmp.resolve("server-repeat.token");
        Files.deleteIfExists(f);
        assertConvergesOnOneValue(() -> {
            Tokens.loadOrCreateSingle(f);
            return Files.readString(f).trim();
        }, 32);
    }

    @Test void singleKeyNoSecretLeakageInExceptionMessage() throws IOException {
        Path dir = tmp.resolve("locked-dir-2");
        Files.createDirectory(dir);
        Path f = dir.resolve("x.token");
        Tokens t = Tokens.loadOrCreateSingle(f);
        String realSecret = Files.readString(f).trim();
        Files.delete(f);

        var original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                t.authenticate("probe-value-that-must-not-leak");
                fail("expected TokensUnavailableException");
            } catch (Tokens.TokensUnavailableException e) {
                String msg = e.getMessage();
                assertNotNull(msg);
                assertFalse(msg.contains("probe-value-that-must-not-leak"));
                assertFalse(msg.contains(realSecret));
            }
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unsupported on this filesystem; nothing to assert here.
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    @Test void singleKeyOldKeyRejectedAfterRotate() throws IOException {
        Path f = tmp.resolve("server.token");
        Tokens t = Tokens.loadOrCreateSingle(f);
        String original = Files.readString(f).trim();
        assertEquals(Tokens.Access.FULL, t.authenticate(original).orElseThrow());

        t.regenerate();
        assertTrue(t.authenticate(original).isEmpty(), "the old key must stop working immediately after regenerate()");
        String rotated = Files.readString(f).trim();
        assertNotEquals(original, rotated);
        assertEquals(Tokens.Access.FULL, t.authenticate(rotated).orElseThrow());
    }

    // ---- two-file (client) FULL/READ split: the same fixture matrix ----

    @Test void clientSplitCreatedWhenMissing() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        assertFalse(Files.exists(full));
        assertFalse(Files.exists(read));
        Tokens t = Tokens.loadOrCreate(full, read);
        assertTrue(Files.isRegularFile(full));
        assertTrue(Files.isRegularFile(read));
        String f1 = Files.readString(full).trim(), r1 = Files.readString(read).trim();
        assertTrue(f1.length() >= 32);
        assertTrue(r1.length() >= 32);
        assertNotEquals(f1, r1);
        assertEquals(Tokens.Access.FULL, t.authenticate(f1).orElseThrow());
        assertEquals(Tokens.Access.READ, t.authenticate(r1).orElseThrow());
    }

    @Test void clientSplitRegeneratesOnDeletionWithoutRestart() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String f1 = Files.readString(full).trim(), r1 = Files.readString(read).trim();

        Files.delete(full);
        assertTrue(t.authenticate(f1).isEmpty(), "old full key must stop working once its file is gone");
        assertTrue(Files.isRegularFile(full), "a missing full token is regenerated on next access, no restart");
        String f2 = Files.readString(full).trim();
        assertNotEquals(f1, f2);
        assertEquals(Tokens.Access.FULL, t.authenticate(f2).orElseThrow());
        // the read token, untouched, still works
        assertEquals(Tokens.Access.READ, t.authenticate(r1).orElseThrow());

        Files.delete(read);
        assertTrue(t.authenticate(r1).isEmpty(), "old read key must stop working once its file is gone");
        assertTrue(Files.isRegularFile(read), "a missing read token is regenerated on next access, no restart");
        String r2 = Files.readString(read).trim();
        assertNotEquals(r1, r2);
        assertEquals(Tokens.Access.READ, t.authenticate(r2).orElseThrow());
    }

    @Test void clientSplitRegeneratesOnWhitespaceOnlyValue() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);

        Files.writeString(full, "   \n\t  ");
        t.authenticate("probe-only-not-the-real-key");
        String f2 = Files.readString(full).trim();
        assertTrue(f2.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(f2).orElseThrow());

        Files.writeString(read, "\n\n   \t");
        t.authenticate("probe-only-not-the-real-key");
        String r2 = Files.readString(read).trim();
        assertTrue(r2.length() >= 32);
        assertEquals(Tokens.Access.READ, t.authenticate(r2).orElseThrow());
    }

    @Test void clientSplitBothTokensStableAcrossRepeatedAccess() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String f1 = Files.readString(full).trim(), r1 = Files.readString(read).trim();
        for (int i = 0; i < 5; i++) {
            t.authenticate(f1);
            t.authenticate(r1);
        }
        assertEquals(f1, Files.readString(full).trim());
        assertEquals(r1, Files.readString(read).trim());

        Tokens t2 = Tokens.loadOrCreate(full, read); // "restart": same files, same secrets
        assertEquals(Tokens.Access.FULL, t2.authenticate(f1).orElseThrow());
        assertEquals(Tokens.Access.READ, t2.authenticate(r1).orElseThrow());
    }

    @Test void clientSplitPersistedValueIsTheOneThatBecomesActive() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        Files.writeString(full, "");
        t.authenticate("anything"); // triggers the refresh/regenerate path
        String onDisk = Files.readString(full).trim();
        assertEquals(Tokens.Access.FULL, t.authenticate(onDisk).orElseThrow());
    }

    @Test void clientSplitOldTokensRejectedAfterRotate() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String f1 = Files.readString(full).trim(), r1 = Files.readString(read).trim();

        t.regenerate();
        assertTrue(t.authenticate(f1).isEmpty(), "the old full token must stop working immediately");
        assertTrue(t.authenticate(r1).isEmpty(), "the old read token must stop working immediately");
        String f2 = Files.readString(full).trim(), r2 = Files.readString(read).trim();
        assertNotEquals(f1, f2);
        assertNotEquals(r1, r2);
        assertEquals(Tokens.Access.FULL, t.authenticate(f2).orElseThrow());
        assertEquals(Tokens.Access.READ, t.authenticate(r2).orElseThrow());
    }

    @Test void clientSplitPersistenceFailureFailsClosedAndRejectsEverything() throws IOException {
        Path dir = tmp.resolve("client-locked-dir");
        Files.createDirectory(dir);
        Path full = dir.resolve("client-full.token"), read = dir.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read); // succeeds while the directory is still writable
        Files.delete(full);
        Files.delete(read);

        var original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            assertThrows(Tokens.TokensUnavailableException.class, () -> t.authenticate("anything"),
                    "a regenerate that cannot persist must fail closed, not hand out an unsaved key");
            assertFalse(Files.exists(full), "no unsaved full key should appear on disk");
            assertFalse(Files.exists(read), "no unsaved read key should appear on disk");
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unsupported on this filesystem; nothing to assert here.
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    @Test void clientSplitConcurrentAccessConvergesOnOneValuePerFile() throws Exception {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        assertConvergesOnOneValue(() -> {
            Tokens t = Tokens.loadOrCreate(full, read); // simulates independent racing accessors
            // Concatenate both so a divergence in either file is caught.
            return Files.readString(full).trim() + "|" + Files.readString(read).trim();
        });
    }

    @Test void clientSplitNoSecretLeakageInExceptionMessage() throws IOException {
        Path dir = tmp.resolve("client-locked-dir-2");
        Files.createDirectory(dir);
        Path full = dir.resolve("client-full.token"), read = dir.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String realFull = Files.readString(full).trim(), realRead = Files.readString(read).trim();
        Files.delete(full);
        Files.delete(read);

        var original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                t.authenticate("probe-value-that-must-not-leak");
                fail("expected TokensUnavailableException");
            } catch (Tokens.TokensUnavailableException e) {
                String msg = e.getMessage();
                assertNotNull(msg);
                assertFalse(msg.contains("probe-value-that-must-not-leak"));
                assertFalse(msg.contains(realFull));
                assertFalse(msg.contains(realRead));
            }
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unsupported on this filesystem; nothing to assert here.
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    // ---- single-key via SecretAccessor: the mechanism the dedicated server actually uses,
    // storing the key as one field inside a larger shared file (ServerApiKeyAccessor does the same
    // thing against ServerConfig.API_KEY inside serverconfig/herobrine_api-server.toml; this fake
    // exercises the identical Tokens.SecretAccessor contract and locking without needing NeoForge).

    @Test void accessorKeyIsCreatedWhenMissingAndLeavesOtherFieldsAlone() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "port=25581", "apiKey=", "maxEditVolume=100000");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        String secret = FakeFieldAccessor.readField(f);
        assertTrue(secret.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(secret).orElseThrow());
        assertEquals(List.of("enabled=true", "port=25581", "apiKey=" + secret, "maxEditVolume=100000"),
                Files.readAllLines(f, StandardCharsets.UTF_8));
    }

    @Test void accessorKeyRegeneratesOnDeletionWithoutRestart() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey=");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        String original = FakeFieldAccessor.readField(f);

        Files.delete(f);
        assertTrue(t.authenticate(original).isEmpty(), "old key must stop working once the backing file is gone");
        assertTrue(Files.isRegularFile(f), "a missing key is regenerated on next access, no restart");
        String regenerated = FakeFieldAccessor.readField(f);
        assertNotEquals(original, regenerated);
        assertEquals(Tokens.Access.FULL, t.authenticate(regenerated).orElseThrow());
    }

    @Test void accessorKeyRegeneratesOnWhitespaceOnlyValue() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey=");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));

        FakeFieldAccessor.writeField(f, "   \t  ");
        t.authenticate("probe-only-not-the-real-key");
        String regenerated = FakeFieldAccessor.readField(f);
        assertTrue(regenerated.length() >= 32);
        assertEquals(Tokens.Access.FULL, t.authenticate(regenerated).orElseThrow());
    }

    @Test void accessorKeyStableAcrossRepeatedAccessAndPreservesOtherFields() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "port=25581", "apiKey=");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        String s1 = FakeFieldAccessor.readField(f);
        for (int i = 0; i < 5; i++) t.authenticate(s1);
        assertEquals(s1, FakeFieldAccessor.readField(f));
        assertEquals(List.of("enabled=true", "port=25581", "apiKey=" + s1), Files.readAllLines(f, StandardCharsets.UTF_8));

        Tokens t2 = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f)); // "restart": same file, same secret
        assertEquals(Tokens.Access.FULL, t2.authenticate(s1).orElseThrow());
    }

    @Test void accessorKeyPersistedValueIsTheOneThatBecomesActive() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "apiKey=");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        FakeFieldAccessor.writeField(f, "");
        t.authenticate("anything");
        String onDisk = FakeFieldAccessor.readField(f);
        assertEquals(Tokens.Access.FULL, t.authenticate(onDisk).orElseThrow());
    }

    @Test void accessorKeyOldKeyRejectedAfterRotate() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey=");
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        String original = FakeFieldAccessor.readField(f);

        t.regenerate();
        assertTrue(t.authenticate(original).isEmpty(), "the old key must stop working immediately after regenerate()");
        String rotated = FakeFieldAccessor.readField(f);
        assertNotEquals(original, rotated);
        assertEquals(Tokens.Access.FULL, t.authenticate(rotated).orElseThrow());
        assertEquals("enabled=true", Files.readAllLines(f, StandardCharsets.UTF_8).get(0));
    }

    @Test void accessorKeyPersistenceFailureOnInitialCreateFailsClosed() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey="); // blank: needs an initial regenerate
        assertThrows(IOException.class, () -> Tokens.loadOrCreateSingle(new FakeFieldAccessor(f, true)));
        assertEquals(List.of("enabled=true", "apiKey="), Files.readAllLines(f, StandardCharsets.UTF_8),
                "nothing should be left behind, and other fields must be untouched, on a failed persist");
    }

    @Test void accessorKeyPersistenceFailureOnLaterRegenerateFailsClosedAndLeaksNoSecret() throws IOException {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey=");
        // Construction succeeds: the initial regenerate's write is allowed through here.
        Tokens setupOnly = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));
        String original = FakeFieldAccessor.readField(f);
        assertTrue(original.length() >= 32);

        // A second Tokens instance, backed by a FAILING accessor, constructed while the field is
        // still valid (so its constructor needs no write and also succeeds) -- this is exactly the
        // "existing controller instance later needs to regenerate" scenario.
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f, true));
        assertEquals(Tokens.Access.FULL, t.authenticate(original).orElseThrow());

        FakeFieldAccessor.writeField(f, ""); // now blank it: the next access must regenerate, and fail
        Tokens.TokensUnavailableException thrown = assertThrows(Tokens.TokensUnavailableException.class,
                () -> t.authenticate("probe-value-that-must-not-leak"),
                "a regenerate that cannot persist must fail closed, not hand out an unsaved key");
        assertFalse(thrown.getMessage().contains("probe-value-that-must-not-leak"));
        assertFalse(thrown.getMessage().contains(original));
        assertEquals("", FakeFieldAccessor.readField(f), "no unsaved key should appear in the field");
        assertEquals("enabled=true", Files.readAllLines(f, StandardCharsets.UTF_8).get(0), "other fields must be untouched");
    }

    @Test void accessorKeyConcurrentAccessConvergesOnOneValue() throws Exception {
        Path f = tmp.resolve("shared.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey=");
        assertConvergesOnOneValue(() -> {
            Tokens.loadOrCreateSingle(new FakeFieldAccessor(f)); // simulates independent racing accessors
            return FakeFieldAccessor.readField(f);
        });
    }

    // ---- writeOwnerOnlyAtomic: the shared permission-preserving persistence FileSecretAccessor
    // and ServerApiKeyAccessor (neoforge) both go through -- a regression here previously let
    // ServerApiKeyAccessor silently drop a hardened config file to default (non-owner-only)
    // permissions on its very first regenerate.

    @Test void writeOwnerOnlyAtomicLeavesABrandNewFileOwnerOnly() throws IOException {
        Path f = tmp.resolve("brand-new.cfg");
        assertFalse(Files.exists(f), "sanity: no predecessor file to inherit permissions from");
        Tokens.writeOwnerOnlyAtomic(f, List.of("enabled=true", "apiKey=abc"));
        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                    "a brand-new file must be owner-only from the start, not just when overwriting an already-restricted one");
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unsupported on this filesystem; nothing to assert here.
        }
    }

    @Test void writeOwnerOnlyAtomicNeverWidensPermissionsAcrossARewrite() throws IOException {
        Path f = tmp.resolve("hardened.cfg");
        Tokens.writeOwnerOnlyAtomic(f, List.of("enabled=true", "apiKey=original"));
        try {
            // Simulate an operator who had additionally hardened the file (defense in depth);
            // the rewrite below must not silently loosen this back to the process umask's default.
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            return; // POSIX permissions unsupported on this filesystem; nothing to assert here.
        }

        Tokens.writeOwnerOnlyAtomic(f, List.of("enabled=true", "apiKey=regenerated"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                "a regenerate-triggered rewrite must never widen the file's permissions");
        assertEquals(List.of("enabled=true", "apiKey=regenerated"), Files.readAllLines(f, StandardCharsets.UTF_8));
    }

    @Test void accessorKeyRegenerationThroughTheRealTokensPathStaysOwnerOnly() throws IOException {
        Path f = tmp.resolve("shared-hardened.cfg");
        FakeFieldAccessor.seed(f, "enabled=true", "apiKey="); // blank: construction must regenerate
        Tokens t = Tokens.loadOrCreateSingle(new FakeFieldAccessor(f));

        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                    "the very first regenerate (brand-new file) must already be owner-only");
        } catch (UnsupportedOperationException e) {
            return; // POSIX permissions unsupported on this filesystem; nothing to assert here.
        }

        // An operator additionally hardens it (still owner-only, just making the intent explicit),
        // then the field is blanked out from under the running process -- authenticate() must
        // regenerate (per the live-disk-read fix) without ever widening the file's permissions.
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        FakeFieldAccessor.writeField(f, "");
        t.authenticate("probe-only-not-the-real-key"); // triggers the regenerate via the real Tokens path

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                "regenerating through Tokens.authenticate() must never widen the file's permissions");
        assertTrue(FakeFieldAccessor.readField(f).length() >= 32);
        assertEquals("enabled=true", Files.readAllLines(f, StandardCharsets.UTF_8).get(0), "other fields untouched");
    }

    /** Simulates the dedicated server's real backing: the secret as one field inside a larger
     * shared file alongside unrelated settings, exercised through the exact same
     * {@link Tokens.SecretAccessor} contract {@code ServerApiKeyAccessor} uses against
     * {@code ServerConfig.API_KEY} -- this is the file-I/O-and-locking half of that mechanism,
     * without depending on NeoForge's config system itself. */
    private static final class FakeFieldAccessor implements Tokens.SecretAccessor {
        private static final String PREFIX = "apiKey=";
        private final Path file;
        private final boolean failWrites;

        FakeFieldAccessor(Path file) { this(file, false); }
        FakeFieldAccessor(Path file, boolean failWrites) { this.file = file; this.failWrites = failWrites; }

        static void seed(Path file, String... lines) throws IOException {
            Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        }

        static String readField(Path file) throws IOException {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                if (line.startsWith(PREFIX)) return line.substring(PREFIX.length());
            throw new IllegalStateException("no apiKey= line in " + file);
        }

        static void writeField(Path file, String rawValue) throws IOException {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : lines) out.add(line.startsWith(PREFIX) ? PREFIX + rawValue : line);
            Files.write(file, out, StandardCharsets.UTF_8);
        }

        @Override public String read() throws IOException {
            if (!Files.isRegularFile(file)) return null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith(PREFIX)) {
                    String v = line.substring(PREFIX.length()).trim();
                    return v.length() >= 32 ? v : null;
                }
            }
            return null;
        }

        @Override public void write(String secret) throws IOException {
            if (failWrites) throw new IOException("simulated persistence failure for " + file);
            List<String> lines = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
            List<String> out = new ArrayList<>();
            boolean replaced = false;
            for (String line : lines) {
                if (line.startsWith(PREFIX)) { out.add(PREFIX + secret); replaced = true; }
                else out.add(line);
            }
            if (!replaced) out.add(PREFIX + secret);
            // Same shared helper ServerApiKeyAccessor uses in the neoforge module -- this fake
            // mirrors that accessor's real persistence, not just its read/write shape.
            Tokens.writeOwnerOnlyAtomic(file, out);
        }

        @Override public Path lockAnchor() { return file; }
    }

    // ---- shared concurrency helper ----

    private interface RacingAccess {
        String call() throws Exception;
    }

    private static void assertConvergesOnOneValue(RacingAccess access) throws Exception {
        assertConvergesOnOneValue(access, 16);
    }

    /** Runs {@code access} concurrently from {@code threads} threads in this same JVM -- the exact
     * scenario that threw OverlappingFileLockException before in-process locking was added -- and
     * asserts every racer observed the same persisted value. */
    private static void assertConvergesOnOneValue(RacingAccess access, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) tasks.add(access::call);
            List<Future<String>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            Set<String> distinct = new java.util.HashSet<>();
            for (Future<String> r : results) distinct.add(r.get());
            assertEquals(1, distinct.size(), "every racing accessor must observe the same persisted value");
        } finally {
            pool.shutdown();
        }
    }

    private static Tokens.Access authenticateAfterRefresh(Tokens t, Path f) throws IOException {
        // authenticate() itself performs the refresh; the presented value doesn't matter for the
        // refresh side-effect, only for which Access (if any) is returned.
        t.authenticate("probe-only-not-the-real-key");
        String current = Files.readString(f).trim();
        return t.authenticate(current).orElseThrow();
    }
}

package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    Tokens t = Tokens.loadOrCreateSingle(f); // simulates independent racing accessors
                    return Files.readString(f).trim();
                });
            }
            List<Future<String>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            Set<String> distinct = new java.util.HashSet<>();
            for (Future<String> r : results) distinct.add(r.get());
            assertEquals(1, distinct.size(), "every racing accessor must observe the same persisted key");
        } finally {
            pool.shutdown();
        }
    }

    // ---- two-file (client) FULL/READ split keeps its own semantics ----

    @Test void clientSplitRegeneratesEachFileIndependentlyOnBlank() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String f1 = Files.readString(full).trim(), r1 = Files.readString(read).trim();
        assertNotEquals(f1, r1);

        Files.writeString(read, ""); // blank just the read token
        assertTrue(t.authenticate(r1).isEmpty());
        String r2 = Files.readString(read).trim();
        assertNotEquals(r1, r2);
        assertEquals(Tokens.Access.READ, t.authenticate(r2).orElseThrow());
        // the full token was untouched by the read token's regeneration
        assertEquals(f1, Files.readString(full).trim());
        assertEquals(Tokens.Access.FULL, t.authenticate(f1).orElseThrow());
    }

    @Test void clientSplitStableAcrossRepeatedAccess() throws IOException {
        Path full = tmp.resolve("client-full.token"), read = tmp.resolve("client-read.token");
        Tokens t = Tokens.loadOrCreate(full, read);
        String f1 = Files.readString(full).trim();
        for (int i = 0; i < 5; i++) t.authenticate(f1);
        assertEquals(f1, Files.readString(full).trim());
    }

    @Test void noSecretAppearsInExceptionMessage() throws IOException {
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

    private static Tokens.Access authenticateAfterRefresh(Tokens t, Path f) throws IOException {
        // authenticate() itself performs the refresh; the presented value doesn't matter for the
        // refresh side-effect, only for which Access (if any) is returned.
        t.authenticate("probe-only-not-the-real-key");
        String current = Files.readString(f).trim();
        return t.authenticate(current).orElseThrow();
    }
}

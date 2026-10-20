package dev.herobrine.api.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.GameThread;
import dev.herobrine.api.core.Tokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ApiController} is the piece shared by the client and dedicated-server lifecycles (see
 * {@code ClientLifecycle} / {@code ServerLifecycle}); this exercises its start/stop idempotency and
 * the platform-specific {@code afterStop} hook with a fake game thread, with no Minecraft classes
 * involved. The dedicated server's real key storage ({@code ServerApiKeyAccessor}, backed by
 * {@code ServerConfig.API_KEY}) needs a fully loaded NeoForge mod config to exercise for real --
 * that is covered end-to-end by a disposable {@code runServer} smoke test (see docs/SERVER.md),
 * not by this unit test; here, a trivial file-backed {@link Tokens.SecretAccessor} stands in for
 * "some non-file-dedicated backing" to exercise {@code ApiController} itself independent of which
 * flavor of {@code Tokens} it was handed. All tokens here are throwaway test fixtures under a
 * JUnit {@code @TempDir}.
 */
class ApiControllerTest {
    @TempDir Path tmp;

    private static ApiContext fakeContext() {
        return new ApiContext(new GameThread() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                CompletableFuture<T> f = new CompletableFuture<>();
                try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); }
                return f;
            }
        });
    }

    /** Minimal stand-in for "a single value in some non-dedicated backing", analogous in shape to
     * {@code ServerApiKeyAccessor} (a field alongside other data) without needing a loaded NeoForge
     * mod config. */
    private static Tokens.SecretAccessor fakeAccessor(Path file) {
        return new Tokens.SecretAccessor() {
            @Override public String read() throws IOException {
                if (!Files.isRegularFile(file)) return null;
                String s = Files.readString(file, StandardCharsets.UTF_8).trim();
                return s.length() >= 32 ? s : null;
            }
            @Override public void write(String secret) throws IOException {
                Files.writeString(file, secret, StandardCharsets.UTF_8);
            }
            @Override public Path lockAnchor() { return file; }
        };
    }

    @Test void startIsIdempotentAndReturnsTheSamePort() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.serverConfigTokens(fakeAccessor(tmp.resolve("key"))), () -> 0, () -> {});
        int port1 = c.start();
        int port2 = c.start();
        assertEquals(port1, port2);
        assertTrue(c.isRunning());
        assertTrue(port1 > 0);
        c.stop();
    }

    @Test void stopRunsAfterStopHookExactlyOnceAndIsIdempotent() throws Exception {
        AtomicInteger hookRuns = new AtomicInteger();
        ApiController c = new ApiController(fakeContext(), ApiController.serverConfigTokens(fakeAccessor(tmp.resolve("key"))), () -> 0, hookRuns::incrementAndGet);
        c.start();
        c.stop();
        c.stop(); // calling stop again while already stopped must not re-run the hook
        assertEquals(1, hookRuns.get());
        assertFalse(c.isRunning());
    }

    @Test void stopBeforeStartIsANoOpAndNeverRunsTheHook() {
        AtomicInteger hookRuns = new AtomicInteger();
        ApiController c = new ApiController(fakeContext(), ApiController.serverConfigTokens(fakeAccessor(tmp.resolve("key"))), () -> 0, hookRuns::incrementAndGet);
        c.stop();
        assertEquals(0, hookRuns.get());
        assertFalse(c.isRunning());
    }

    @Test void regenerateTokensWorksBeforeAndAfterStart() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.serverConfigTokens(fakeAccessor(tmp.resolve("key"))), () -> 0, () -> {});
        c.regenerateTokens(); // before any start: loads-or-creates, then rotates
        c.start();
        c.regenerateTokens(); // after start: still works, same cached Tokens instance
        c.stop();
    }

    @Test void clientTokensLoaderCreatesTheExpectedFullAndReadFiles() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.clientTokens(tmp), () -> 0, () -> {});
        c.start();
        assertTrue(Files.isRegularFile(tmp.resolve(ApiController.CLIENT_FULL_TOKEN_FILE)));
        assertTrue(Files.isRegularFile(tmp.resolve(ApiController.CLIENT_READ_TOKEN_FILE)));
        c.stop();
    }

    @Test void serverConfigTokensLoaderCreatesAndRegeneratesViaTheAccessor() throws Exception {
        Path key = tmp.resolve("key");
        ApiController c = new ApiController(fakeContext(), ApiController.serverConfigTokens(fakeAccessor(key)), () -> 0, () -> {});
        c.start();
        assertTrue(Files.isRegularFile(key));
        assertTrue(Files.readString(key).trim().length() >= 32);
        c.stop();
    }
}

package dev.herobrine.api.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.GameThread;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ApiController} is the piece shared by the client and dedicated-server lifecycles (see
 * {@code ClientLifecycle} / {@code ServerLifecycle}); this exercises its start/stop idempotency and
 * the platform-specific {@code afterStop} hook with a fake game thread and the server's single-key
 * token loader, with no Minecraft or NeoForge classes involved. All tokens here are throwaway test
 * fixtures under a JUnit {@code @TempDir}.
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

    @Test void startIsIdempotentAndReturnsTheSamePort() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.serverTokens(tmp), () -> 0, () -> {});
        int port1 = c.start();
        int port2 = c.start();
        assertEquals(port1, port2);
        assertTrue(c.isRunning());
        assertTrue(port1 > 0);
        c.stop();
    }

    @Test void stopRunsAfterStopHookExactlyOnceAndIsIdempotent() throws Exception {
        AtomicInteger hookRuns = new AtomicInteger();
        ApiController c = new ApiController(fakeContext(), ApiController.serverTokens(tmp), () -> 0, hookRuns::incrementAndGet);
        c.start();
        c.stop();
        c.stop(); // calling stop again while already stopped must not re-run the hook
        assertEquals(1, hookRuns.get());
        assertFalse(c.isRunning());
    }

    @Test void stopBeforeStartIsANoOpAndNeverRunsTheHook() {
        AtomicInteger hookRuns = new AtomicInteger();
        ApiController c = new ApiController(fakeContext(), ApiController.serverTokens(tmp), () -> 0, hookRuns::incrementAndGet);
        c.stop();
        assertEquals(0, hookRuns.get());
        assertFalse(c.isRunning());
    }

    @Test void regenerateTokensWorksBeforeAndAfterStart() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.serverTokens(tmp), () -> 0, () -> {});
        c.regenerateTokens(); // before any start: loads-or-creates, then rotates
        c.start();
        c.regenerateTokens(); // after start: still works, same cached Tokens instance
        c.stop();
    }

    @Test void clientTokensLoaderCreatesTheExpectedFullAndReadFiles() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.clientTokens(tmp), () -> 0, () -> {});
        c.start();
        assertTrue(java.nio.file.Files.isRegularFile(tmp.resolve(ApiController.CLIENT_FULL_TOKEN_FILE)));
        assertTrue(java.nio.file.Files.isRegularFile(tmp.resolve(ApiController.CLIENT_READ_TOKEN_FILE)));
        c.stop();
    }

    @Test void serverTokensLoaderCreatesTheExpectedSingleFile() throws Exception {
        ApiController c = new ApiController(fakeContext(), ApiController.serverTokens(tmp), () -> 0, () -> {});
        c.start();
        assertTrue(java.nio.file.Files.isRegularFile(tmp.resolve(ApiController.SERVER_TOKEN_FILE)));
        c.stop();
    }
}

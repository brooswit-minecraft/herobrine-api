package dev.herobrine.api.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class GameThreadHopTest {
    ExecutorService server;
    GameThread serverThread;

    @BeforeEach void setUp() {
        server = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-server-thread"));
        serverThread = new GameThread() {
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                CompletableFuture<T> f = new CompletableFuture<>();
                server.execute(() -> {
                    try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); }
                });
                return f;
            }
        };
    }

    @AfterEach void tearDown() { server.shutdownNow(); }

    @Test void runsOnTheTargetThreadAndReturnsItsResult() {
        assertEquals("fake-server-thread", GameThreadHop.call(serverThread, false, 1_000, 1_000, () -> Thread.currentThread().getName()));
    }

    @Test void alreadyOnTheTargetThreadRunsInline() {
        GameThread neverUsed = new GameThread() {
            public <T> CompletableFuture<T> submit(Callable<T> task) { throw new AssertionError("must not hop to itself"); }
        };
        assertEquals(Thread.currentThread().getName(), GameThreadHop.call(neverUsed, true, 1, 1, () -> Thread.currentThread().getName()));
    }

    @Test void apiErrorsFromTheTargetThreadReachTheCallerUnchanged() {
        ApiException e = assertThrows(ApiException.class, () -> GameThreadHop.call(serverThread, false, 1_000, 1_000, () -> {
            throw ApiException.forbidden("nope");
        }));
        assertEquals(403, e.status);
        assertEquals("nope", e.getMessage());
    }

    @Test void aTaskTheTargetNeverStartedIsWithdrawnAndNeverRuns() {
        List<Runnable> queued = new ArrayList<>();
        GameThread stalled = new GameThread() {
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                CompletableFuture<T> f = new CompletableFuture<>();
                queued.add(() -> { try { f.complete(task.call()); } catch (Throwable t) { f.completeExceptionally(t); } });
                return f;
            }
        };
        AtomicBoolean ran = new AtomicBoolean();
        ApiException e = assertThrows(ApiException.class, () -> GameThreadHop.call(stalled, false, 20, 1_000, () -> { ran.set(true); return 1; }));
        assertEquals(503, e.status);
        assertTrue(e.getMessage().contains("nothing was done"));
        queued.forEach(Runnable::run); // the target thread gets to it late: it must refuse, not edit
        assertFalse(ran.get());
    }

    @Test void aTaskThatStartedInTimeIsWaitedForEvenPastTheStartTimeout() {
        assertEquals("done", GameThreadHop.call(serverThread, false, 20, 5_000, () -> {
            Thread.sleep(200);
            return "done";
        }));
    }

    @Test void aStartedTaskThatNeverFinishesIsReportedAsUnknownNotAsNothingDone() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ApiException e = assertThrows(ApiException.class, () -> GameThreadHop.call(serverThread, false, 20, 100, () -> {
            release.await(5, TimeUnit.SECONDS);
            return 1;
        }));
        release.countDown();
        assertEquals(503, e.status);
        assertTrue(e.getMessage().contains("outcome is unknown"));
    }
}

package dev.herobrine.api.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a task on another game thread and waits for it: e.g. a client-side request whose work belongs to
 * the integrated server's thread, which owns the server world. Keeps {@link ApiServer}'s rule that a
 * request refused for lateness did nothing: a task the target thread has not started within
 * {@code startTimeoutMs} is withdrawn and never runs, while one that has started is changing the world, so
 * its real outcome is waited for (up to {@code finishTimeoutMs}) instead of being reported as not done.
 */
public final class GameThreadHop {
    private GameThreadHop() {}

    private static final int PENDING = 0, STARTED = 1, WITHDRAWN = 2;

    /**
     * @param onTarget whether the caller is already on {@code target}'s thread; the task then runs inline,
     *                 since waiting on your own thread would deadlock
     */
    public static <T> T call(GameThread target, boolean onTarget, long startTimeoutMs, long finishTimeoutMs, Callable<T> task) {
        if (onTarget) return callHere(task);
        AtomicInteger state = new AtomicInteger(PENDING);
        CompletableFuture<T> f = target.submit(() -> {
            if (!state.compareAndSet(PENDING, STARTED))
                throw new ApiException(503, "GAME_THREAD_BUSY", "The server thread did not start the request in time; nothing was done");
            return task.call();
        });
        try {
            try {
                return f.get(startTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException late) {
                if (state.compareAndSet(PENDING, WITHDRAWN))
                    throw new ApiException(503, "GAME_THREAD_BUSY", "The server thread did not start the request in time; nothing was done");
                return f.get(finishTimeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException ae) throw ae;
            throw new ApiException(500, "ACTION_FAILED", String.valueOf(e.getCause()));
        } catch (TimeoutException e) {
            throw new ApiException(503, "GAME_THREAD_BUSY", "The server thread started the request but has not finished it; its outcome is unknown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "ACTION_FAILED", "Interrupted while waiting for the server thread");
        }
    }

    private static <T> T callHere(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "ACTION_FAILED", String.valueOf(e));
        }
    }
}

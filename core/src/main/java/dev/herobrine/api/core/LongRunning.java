package dev.herobrine.api.core;

/** Returned by an action handler to run work across ticks. Polled on the game thread once per tick. */
public interface LongRunning {
    /** @return a non-null result when finished, or null to keep running. */
    Object tick();

    default void cancel() {}

    /** Optional progress snapshot included in operation status. */
    default Object progress() { return null; }
}

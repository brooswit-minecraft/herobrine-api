package dev.herobrine.api.core;

/** Configurable resource limits. */
public record Limits(
        int maxConcurrentRequests,
        int maxResponseBytes,
        int maxRequestBytes,
        int maxEventStreams,
        long gameThreadTimeoutMs,
        int maxPageSize) {

    public static Limits defaults() {
        return new Limits(32, 8 << 20, 1 << 20, 8, 10_000, 1000);
    }
}

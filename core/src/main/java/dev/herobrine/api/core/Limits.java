package dev.herobrine.api.core;

/** Configurable resource limits (spec 13.2). */
public record Limits(
        int maxConcurrentRequests,
        int maxRequestsPerSecondPerToken,
        int maxResponseBytes,
        int maxRequestBytes,
        int maxEventStreams,
        long gameThreadTimeoutMs,
        int maxPageSize,
        int maxBatchSize) {

    public static Limits defaults() {
        return new Limits(32, 200, 8 << 20, 1 << 20, 8, 10_000, 1000, 100);
    }
}

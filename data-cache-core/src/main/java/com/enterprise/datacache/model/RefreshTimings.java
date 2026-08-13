package com.enterprise.datacache.model;

/**
 * Fine-grained timing breakdown for a single refresh attempt, in milliseconds.
 * Used both for structured logging/metrics and for returning diagnostics to callers so that
 * the dominant bottleneck (Dremio execution vs. Arrow transfer vs. DuckDB write vs. validation)
 * can be identified without guesswork.
 */
public record RefreshTimings(
        long dremioSetupMs,
        long timeToFirstBatchMs,
        long arrowTransferMs,
        long duckDbWriteMs,
        long validationMs,
        long activationMs,
        long totalMs,
        long rowsLoaded,
        long bytesLoaded) {

    public static RefreshTimings zero() {
        return new RefreshTimings(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public double rowsPerSecond() {
        return totalMs <= 0 ? 0.0 : (rowsLoaded * 1000.0) / totalMs;
    }

    public double megabytesPerSecond() {
        return totalMs <= 0 ? 0.0 : (bytesLoaded / (1024.0 * 1024.0)) / (totalMs / 1000.0);
    }
}

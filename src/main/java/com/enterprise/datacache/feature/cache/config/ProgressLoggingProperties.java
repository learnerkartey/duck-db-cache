package com.enterprise.datacache.feature.cache.config;

import java.time.Duration;

/**
 * Controls INFO-level progress logging during dataset refresh (streaming rows from Dremio into
 * DuckDB): {@code data-cache.logging.progress.*}. A progress log line is emitted whenever
 * {@code rowInterval} rows have been processed since the last one OR {@code timeInterval} has
 * elapsed since the last one - whichever happens first - so both very fast and very slow sources
 * produce a steady, bounded rate of log lines rather than either flooding the log or going silent
 * for minutes. Individual Arrow batches are never logged at INFO regardless of this configuration
 * - only DEBUG, if at all.
 */
public class ProgressLoggingProperties {

    /** Master switch for progress logging. When false, no {@code dataset-load-progress} lines are emitted. */
    private boolean enabled = true;

    /** Emit a progress line after at least this many rows have been processed since the last one. */
    private long rowInterval = 1_000_000L;

    /** Emit a progress line after at least this much time has elapsed since the last one. */
    private Duration timeInterval = Duration.ofSeconds(30);

    /**
     * Include the BUILDING DuckDB file's current on-disk size in each progress line. The
     * filesystem is only queried when a progress line is actually about to be emitted - never per
     * batch or per row.
     */
    private boolean includeFileSize = true;

    /** Include the running Arrow batch count in each progress line. */
    private boolean includeBatchCount = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRowInterval() {
        return rowInterval;
    }

    public void setRowInterval(long rowInterval) {
        this.rowInterval = rowInterval;
    }

    public Duration getTimeInterval() {
        return timeInterval;
    }

    public void setTimeInterval(Duration timeInterval) {
        this.timeInterval = timeInterval;
    }

    public boolean isIncludeFileSize() {
        return includeFileSize;
    }

    public void setIncludeFileSize(boolean includeFileSize) {
        this.includeFileSize = includeFileSize;
    }

    public boolean isIncludeBatchCount() {
        return includeBatchCount;
    }

    public void setIncludeBatchCount(boolean includeBatchCount) {
        this.includeBatchCount = includeBatchCount;
    }
}

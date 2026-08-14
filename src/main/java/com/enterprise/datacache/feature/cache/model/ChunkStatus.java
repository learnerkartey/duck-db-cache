package com.enterprise.datacache.feature.cache.model;

/**
 * Lifecycle of one resumable-refresh chunk. The durable checkpoint a restart relies on is a chunk
 * reaching {@link #COMPLETED} - never an in-memory row count, never "last row observed from Arrow".
 */
public enum ChunkStatus {
    /** Planned but not yet attempted. */
    PENDING,
    /** Currently being loaded by a live process. Any chunk found RUNNING at startup is a crash
     *  artifact (no process could still be running it) and must be reset to {@link #PENDING}. */
    RUNNING,
    /** Its rows are durably committed to the BUILDING DuckDB file and its completion metadata is
     *  persisted. Never reloaded again unless the whole version is abandoned. */
    COMPLETED,
    /** The most recent attempt failed; retryable on the next attempt. */
    FAILED
}

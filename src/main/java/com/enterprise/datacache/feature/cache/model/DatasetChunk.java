package com.enterprise.datacache.feature.cache.model;

import java.time.Instant;

/**
 * Persistent record of one resumable-refresh chunk's state. Restart recovery reconstructs "what's
 * left to load" entirely from these rows plus {@link RefreshManifest} - never from in-memory
 * counters, which do not survive a crash.
 */
public record DatasetChunk(
        String datasetName,
        long version,
        long chunkId,
        String partitionStart,
        String partitionEnd,
        ChunkStatus status,
        long rowsLoaded,
        Instant startedAt,
        Instant completedAt,
        int attemptCount,
        String errorCode,
        String errorSummary) {

    public ChunkDefinition toDefinition() {
        return new ChunkDefinition(chunkId, partitionStart, partitionEnd);
    }
}

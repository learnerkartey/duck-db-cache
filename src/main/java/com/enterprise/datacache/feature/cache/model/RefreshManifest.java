package com.enterprise.datacache.feature.cache.model;

import java.time.Instant;

/**
 * Persistent record of one resumable BUILDING attempt - the durable source of truth restart
 * recovery reconstructs progress from. One row per {@code (datasetName, version)}; a fresh
 * {@code refreshId} is minted only when a brand-new BUILDING version is created (never on resume,
 * which reuses the same version/refreshId chunk-for-chunk).
 */
public record RefreshManifest(
        String datasetName,
        long version,
        String refreshId,
        String sourceSqlHash,
        String sourceSchemaHash,
        boolean resumeEnabled,
        ResumeStrategyType resumeStrategy,
        String partitionColumn,
        Long chunkSize,
        String sourceSnapshotId,
        ConsistencyMode consistencyMode,
        Long totalChunks,
        long completedChunks,
        long failedChunks,
        long rowsCommitted,
        RefreshManifestStatus status,
        Instant startedAt,
        Instant lastUpdatedAt) {

    public boolean isFullyPlanned() {
        return totalChunks != null;
    }
}

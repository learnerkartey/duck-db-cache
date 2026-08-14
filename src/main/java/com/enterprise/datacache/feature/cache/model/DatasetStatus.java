package com.enterprise.datacache.feature.cache.model;

import java.time.Instant;

/**
 * Point-in-time status snapshot for a single configured dataset, used by {@code DataCacheStatusService}.
 * The {@code activeVersion} a query would currently be served from is always distinct from
 * {@code buildingVersion} - a refresh in progress never affects what's ACTIVE until it succeeds,
 * so operators can see at a glance that queries are still being served by the old version while a
 * new one builds.
 */
public record DatasetStatus(
        String datasetName,
        boolean enabled,
        Long activeVersion,
        Long previousVersion,
        Long activeRowCount,
        Instant lastSuccessfulRefresh,
        RefreshOutcome lastRefreshStatus,
        Long lastRefreshDurationMs,
        String lastError,
        boolean refreshInProgress,
        Long buildingVersion,
        RefreshStage refreshStage,
        Long rowsProcessed,
        Long batchesProcessed,
        Long elapsedMs,
        Double averageRowsPerSecond,
        Double estimatedPercent,
        boolean resumable,
        Boolean resuming,
        Long completedChunks,
        Long totalChunks,
        Long rowsCommitted,
        Long currentChunk,
        Long failedChunk,
        String sourceSnapshotId,
        boolean pausedRetryable) {

    /**
     * Derived, never stored separately: a dataset can only have {@code activeVersion == null} and
     * {@code refreshInProgress == true} simultaneously during its very first-ever load - ACTIVE is
     * only ever replaced atomically at the end of a successful refresh, so once a dataset has an
     * ACTIVE version it always has one (old or new) for the rest of its lifetime.
     */
    public DatasetAvailability availability() {
        if (activeVersion != null) {
            return DatasetAvailability.AVAILABLE;
        }
        return refreshInProgress ? DatasetAvailability.STARTUP_LOADING : DatasetAvailability.UNAVAILABLE;
    }
}

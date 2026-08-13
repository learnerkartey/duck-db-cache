package com.enterprise.datacache.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Immutable persistent metadata record describing a single physical version of a dataset.
 * Maps 1:1 to a row in the {@code cache_metadata.duckdb} versions table.
 */
public record DatasetVersion(
        String datasetName,
        long version,
        VersionState state,
        Path filePath,
        String tableName,
        long rowCount,
        long bytesLoaded,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String sourceSqlHash,
        ValidationStatus validationStatus,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isTerminalSuccess() {
        return state == VersionState.ACTIVE || state == VersionState.PREVIOUS;
    }

    public DatasetVersion withState(VersionState newState, Instant updatedAt) {
        return new DatasetVersion(datasetName, version, newState, filePath, tableName, rowCount, bytesLoaded,
                startedAt, completedAt, durationMs, sourceSqlHash, validationStatus, errorCode, errorSummary,
                createdAt, updatedAt);
    }
}

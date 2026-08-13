package com.enterprise.datacache.model;

import java.time.Instant;

/** Point-in-time status snapshot for a single configured dataset, used by {@code DataCacheStatusService}. */
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
        boolean refreshInProgress) {
}

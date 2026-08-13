package com.enterprise.datacache.feature.cache.model;

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

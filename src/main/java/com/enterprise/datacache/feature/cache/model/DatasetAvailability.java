package com.enterprise.datacache.feature.cache.model;

/**
 * Query-time availability of a dataset, derived from {@link DatasetStatus} (never stored
 * separately, so it can never drift from the metadata store / refresh lock it is computed from).
 */
public enum DatasetAvailability {

    /** Has a usable ACTIVE version right now. Queries against it succeed. */
    AVAILABLE,

    /** No ACTIVE version yet, and a refresh (necessarily its first ever) is currently in progress. */
    STARTUP_LOADING,

    /** No ACTIVE version, and nothing is currently loading - a prior load either failed or was never attempted. */
    UNAVAILABLE
}

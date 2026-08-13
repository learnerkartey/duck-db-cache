package com.enterprise.datacache.feature.cache.config;

/** Global execution strategy for the datasets that {@link StartupMode} decides need a startup load. */
public enum StartupExecutionMode {

    /**
     * The application starts and reports ready normally. Datasets with no existing ACTIVE version
     * load in the background; queries against a dataset that is still loading fail cleanly with
     * {@code DATASET_NOT_AVAILABLE} (reason: still loading) until the load completes. Recommended
     * default - never delays application startup.
     */
    ASYNC,

    /**
     * After triggering every required startup load, the startup coordinator blocks (up to
     * {@code data-cache.startup.timeout}) waiting for all datasets marked
     * {@code required: true} to reach a usable ACTIVE version. The application process itself is
     * never held past the timeout - on timeout the wait simply ends and loading continues in the
     * background - but the {@code dataCacheReadiness} health indicator reports the readiness
     * probe as DOWN until every required dataset is actually ready.
     */
    BLOCK_UNTIL_REQUIRED_CACHE_READY
}

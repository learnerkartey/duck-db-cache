package com.enterprise.datacache.feature.cache.model;

/** What caused a dataset refresh to start - included on every progress log line and in the status API. */
public enum RefreshTrigger {
    /** Fired by {@code DataCacheStartupCoordinator} during application startup. */
    STARTUP,
    /** Fired by {@code DynamicRefreshScheduler}'s per-dataset cron trigger. */
    SCHEDULED,
    /** A single dataset refresh explicitly requested via the REST admin API or {@code DataCacheRefreshService}. */
    MANUAL,
    /** One of several datasets refreshed together via {@code DataCacheRefreshService#refreshAll()}. */
    REFRESH_ALL
}

package com.enterprise.datacache.feature.cache.model;

/** Result returned to callers of {@code DataCacheRefreshService#refresh}. */
public record DatasetRefreshResult(
        String datasetName,
        RefreshOutcome outcome,
        Long newVersion,
        Long activeVersion,
        RefreshTimings timings,
        String errorCode,
        String errorMessage,
        int attemptsMade) {

    public boolean isSuccess() {
        return outcome == RefreshOutcome.SUCCESS;
    }
}

package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Triggers dataset refreshes. Every refresh - whether triggered here, from the REST admin API, the
 * dynamic cron scheduler, or application startup - goes through the same bounded executor and the
 * same progress-tracking pipeline, so {@code data-cache.refresh.max-concurrent-datasets} is always
 * respected and progress logs/status always behave identically regardless of trigger source.
 */
public interface DataCacheRefreshService {

    /** Same as {@link #refresh(String, RefreshTrigger)} with {@link RefreshTrigger#MANUAL}. */
    default DatasetRefreshResult refresh(String datasetName) {
        return refresh(datasetName, RefreshTrigger.MANUAL);
    }

    /** Refreshes one dataset and blocks until it completes (success, failure, or already-running). */
    DatasetRefreshResult refresh(String datasetName, RefreshTrigger trigger);

    /** Same as {@link #refreshAsync(String, RefreshTrigger)} with {@link RefreshTrigger#MANUAL}. */
    default CompletableFuture<DatasetRefreshResult> refreshAsync(String datasetName) {
        return refreshAsync(datasetName, RefreshTrigger.MANUAL);
    }

    /** Same as {@link #refresh(String, RefreshTrigger)} but does not block the calling thread. */
    CompletableFuture<DatasetRefreshResult> refreshAsync(String datasetName, RefreshTrigger trigger);

    /**
     * Refreshes every enabled dataset (tagged {@link RefreshTrigger#REFRESH_ALL}), respecting the
     * configured concurrency bound, and waits for all to finish.
     */
    Map<String, DatasetRefreshResult> refreshAll();

    /**
     * Explicitly resumes a dataset's paused/interrupted BUILDING version. Behaves exactly like
     * {@link #refresh(String)} except it first confirms a resumable BUILDING manifest actually
     * exists - if not, returns {@link com.enterprise.datacache.feature.cache.model.RefreshOutcome#NO_RESUMABLE_BUILD}
     * without triggering any work, rather than silently starting a brand-new full load. Automatic
     * startup resume does not depend on this method being called - it is a convenience entry point
     * for operators who want to explicitly continue a known paused build.
     */
    DatasetRefreshResult resume(String datasetName);

    /**
     * Intentionally abandons any partial BUILDING version for this dataset (even if it would
     * otherwise be safely resumable) and starts a brand-new version from zero. The current ACTIVE
     * version is never touched - queries keep being served throughout. Intended as a protected
     * admin operation.
     */
    DatasetRefreshResult restartRefresh(String datasetName);
}

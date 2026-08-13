package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Triggers dataset refreshes. Every refresh - whether triggered here, from the REST admin API, or
 * from the dynamic cron scheduler - goes through the same bounded executor, so
 * {@code data-cache.refresh.max-concurrent-datasets} is always respected regardless of trigger
 * source.
 */
public interface DataCacheRefreshService {

    /** Refreshes one dataset and blocks until it completes (success, failure, or already-running). */
    DatasetRefreshResult refresh(String datasetName);

    /** Same as {@link #refresh(String)} but does not block the calling thread. */
    CompletableFuture<DatasetRefreshResult> refreshAsync(String datasetName);

    /** Refreshes every enabled dataset, respecting the configured concurrency bound, and waits for all to finish. */
    Map<String, DatasetRefreshResult> refreshAll();
}

package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live {@link RefreshProgress} for whatever refresh attempt is currently (or most
 * recently) running per dataset. {@link RefreshLock} already guarantees at most one refresh per
 * dataset at a time, so keying by dataset name alone keeps every dataset's counters completely
 * isolated from every other dataset's - there is never one global counter shared across datasets,
 * and starting a new attempt (a retry, or a fresh refresh) always replaces the previous entry with
 * a brand-new {@link RefreshProgress} rather than reusing or resetting counters in place.
 */
public class RefreshProgressRegistry {

    private final ConcurrentHashMap<String, RefreshProgress> byDataset = new ConcurrentHashMap<>();
    private final DataCacheMetrics metrics;

    public RefreshProgressRegistry(DataCacheMetrics metrics) {
        this.metrics = metrics;
    }

    /** Starts tracking a new attempt, replacing whatever progress was left over from a previous attempt/version. */
    public RefreshProgress start(String datasetName, long version, RefreshTrigger trigger, Long previousVersionRowCount,
            Long expectedRowCount) {
        RefreshProgress progress = new RefreshProgress(datasetName, version, trigger, previousVersionRowCount,
                expectedRowCount);
        byDataset.put(datasetName, progress);
        metrics.bindProgressGauges(datasetName, this);
        return progress;
    }

    /** The most recent refresh attempt tracked for this dataset, if any refresh has ever run in this process. */
    public Optional<RefreshProgress> find(String datasetName) {
        return Optional.ofNullable(byDataset.get(datasetName));
    }
}

package com.enterprise.datacache.feature.cache.metrics;

import com.enterprise.datacache.feature.cache.model.RefreshTimings;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgress;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgressRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Central Micrometer instrumentation point. Tag values are always drawn from configured dataset
 * and query names (a small, bounded set from {@code data-cache.*} configuration) - never from
 * request parameters or row data - to keep metric cardinality bounded.
 */
public class DataCacheMetrics {

    private final MeterRegistry registry;
    private final Set<String> progressGaugesBound = ConcurrentHashMap.newKeySet();

    public DataCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRefreshSuccess(String dataset, RefreshTimings timings) {
        Tags tags = Tags.of("dataset", dataset, "outcome", "success");
        Timer.builder("datacache.refresh.duration").tags(tags).register(registry)
                .record(timings.totalMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "dremio_setup").record(timings.dremioSetupMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "time_to_first_batch").record(timings.timeToFirstBatchMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "arrow_transfer").record(timings.arrowTransferMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "duckdb_write").record(timings.duckDbWriteMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "validation").record(timings.validationMs(), TimeUnit.MILLISECONDS);
        phaseTimer(dataset, "activation").record(timings.activationMs(), TimeUnit.MILLISECONDS);
        DistributionSummary.builder("datacache.refresh.rows").tag("dataset", dataset).register(registry)
                .record(timings.rowsLoaded());
        DistributionSummary.builder("datacache.refresh.bytes").tag("dataset", dataset).register(registry)
                .record(timings.bytesLoaded());
        registry.gauge("datacache.refresh.rows_per_second", Tags.of("dataset", dataset), timings.rowsPerSecond());
        Counter.builder("datacache.refresh.count").tags(tags).register(registry).increment();
    }

    public void recordRefreshFailure(String dataset, String errorCode) {
        Counter.builder("datacache.refresh.count")
                .tags(Tags.of("dataset", dataset, "outcome", "failure"))
                .register(registry).increment();
        Counter.builder("datacache.refresh.failures")
                .tags(Tags.of("dataset", dataset, "errorCode", errorCode == null ? "UNKNOWN" : errorCode))
                .register(registry).increment();
    }

    private Timer phaseTimer(String dataset, String phase) {
        return Timer.builder("datacache.refresh.phase.duration").tag("dataset", dataset).tag("phase", phase)
                .register(registry);
    }

    public Timer.Sample startQueryTimer() {
        return Timer.start(registry);
    }

    public void recordQuerySuccess(String queryName, Timer.Sample sample) {
        sample.stop(Timer.builder("datacache.query.duration")
                .tags(Tags.of("query", queryName, "outcome", "success")).register(registry));
        Counter.builder("datacache.query.count").tags(Tags.of("query", queryName, "outcome", "success"))
                .register(registry).increment();
    }

    public void recordQueryFailure(String queryName, Timer.Sample sample) {
        sample.stop(Timer.builder("datacache.query.duration")
                .tags(Tags.of("query", queryName, "outcome", "failure")).register(registry));
        Counter.builder("datacache.query.count").tags(Tags.of("query", queryName, "outcome", "failure"))
                .register(registry).increment();
    }

    public void setActiveReaders(String dataset, long version, int count) {
        registry.gauge("datacache.version.active_readers", Tags.of("dataset", dataset), count);
    }

    /**
     * Binds live in-progress-refresh gauges for {@code dataset}, reading through to whatever
     * {@link RefreshProgress} {@code progressRegistry} currently holds for it each time a scrape
     * happens (0 if none has ever run, or if the dataset's last-known attempt is being read after
     * it finished). Tagged only by {@code dataset} - never by version - so cardinality stays
     * bounded by the number of configured datasets regardless of how many versions a dataset
     * accumulates over the application's lifetime. Idempotent: safe to call every time a refresh
     * starts, not just the first time.
     */
    public void bindProgressGauges(String dataset, RefreshProgressRegistry progressRegistry) {
        if (!progressGaugesBound.add(dataset)) {
            return;
        }
        Tags tags = Tags.of("dataset", dataset);
        registry.gauge("datacache.refresh.progress.rows_processed", tags, progressRegistry,
                r -> r.find(dataset).map(RefreshProgress::rowsProcessed).orElse(0L).doubleValue());
        registry.gauge("datacache.refresh.progress.batches_processed", tags, progressRegistry,
                r -> r.find(dataset).map(RefreshProgress::batchesProcessed).orElse(0L).doubleValue());
        registry.gauge("datacache.refresh.progress.elapsed_ms", tags, progressRegistry,
                r -> r.find(dataset).map(RefreshProgress::elapsedMs).orElse(0L).doubleValue());
        registry.gauge("datacache.refresh.progress.rows_per_second", tags, progressRegistry,
                r -> r.find(dataset).map(RefreshProgress::averageRowsPerSecond).orElse(0.0));
    }
}

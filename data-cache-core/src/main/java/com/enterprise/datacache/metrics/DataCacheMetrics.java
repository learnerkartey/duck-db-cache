package com.enterprise.datacache.metrics;

import com.enterprise.datacache.model.RefreshTimings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * Central Micrometer instrumentation point. Tag values are always drawn from configured dataset
 * and query names (a small, bounded set from {@code data-cache.*} configuration) - never from
 * request parameters or row data - to keep metric cardinality bounded.
 */
public class DataCacheMetrics {

    private final MeterRegistry registry;

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
}

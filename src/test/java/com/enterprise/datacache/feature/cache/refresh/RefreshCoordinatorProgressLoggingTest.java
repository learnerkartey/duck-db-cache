package com.enterprise.datacache.feature.cache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.config.RetryProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import com.enterprise.datacache.feature.cache.resume.ResumableRefreshExecutor;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import com.enterprise.datacache.feature.cache.testsupport.InMemoryDremioSource;
import com.enterprise.datacache.feature.cache.testsupport.PartialStreamThenFailDremioSource;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import com.enterprise.datacache.feature.cache.validation.DatasetValidationService;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * End-to-end tests of progress logging through a real {@link RefreshCoordinator} run - real
 * DuckDB files, real batches, a captured log appender - proving the row/time threshold gate
 * actually governs what gets logged during a live refresh, that a final accurate summary is always
 * produced regardless of whether any periodic threshold ever fired, and that a mid-stream failure
 * leaves an accurate partial row count behind. Uses small synthetic batches, not 30M rows -
 * {@link RefreshProgressTest} already covers the threshold arithmetic itself precisely with a fake
 * clock; this suite proves the wiring between {@link RefreshCoordinator} and {@link RefreshProgress}
 * is correct.
 */
class RefreshCoordinatorProgressLoggingTest {

    @TempDir
    Path tempDir;

    private final RetrySleeper noOpSleeper = duration -> { /* no real sleeping in tests */ };

    private Logger coordinatorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        coordinatorLogger = (Logger) LoggerFactory.getLogger(RefreshCoordinator.class);
        appender = new ListAppender<>();
        appender.start();
        coordinatorLogger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingLogs() {
        coordinatorLogger.detachAppender(appender);
    }

    private DataCacheProperties baseProperties(long rowInterval, Duration timeInterval) {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getLogging().getProgress().setRowInterval(rowInterval);
        properties.getLogging().getProgress().setTimeInterval(timeInterval);

        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName("financial");
        dataset.setSourceSql("classpath:testdata/test-dataset.sql");
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(1);
        retry.setInitialDelay(Duration.ofMillis(1));
        dataset.setRetry(retry);
        properties.getDatasets().put("financial", dataset);
        return properties;
    }

    private RefreshCoordinator newCoordinator(DataCacheProperties properties, DremioSource source,
            MetadataStore metadataStore, VersionManager versionManager, RefreshProgressRegistry progressRegistry) {
        DataCacheMetrics metrics = new DataCacheMetrics(new SimpleMeterRegistry());
        return new RefreshCoordinator(properties, source, new SqlResourceLoader(), metadataStore, versionManager,
                new DatasetValidationService(new SqlResourceLoader()), new RefreshLock(),
                new RetryExecutor(noOpSleeper), metrics, progressRegistry,
                new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore));
    }

    private MetadataStore newMetadataStore(DuckDbProperties duckDbProps) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
    }

    private List<String> progressLogMessages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("event=dataset-load-progress"))
                .toList();
    }

    @Test
    void rowThresholdBelowTotalRowsProducesAtLeastOneProgressLogDuringARealRefresh() {
        // 10,000 rows in batches of 1,000, logging every 2,000 rows processed: several thresholds
        // must fire well before the load finishes.
        DataCacheProperties properties = baseProperties(2_000, Duration.ofHours(1));
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                InMemoryDremioSource source = new InMemoryDremioSource(10_000, 1_000)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(new DataCacheMetrics(new SimpleMeterRegistry()));
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager, progressRegistry);

            DatasetRefreshResult result = coordinator.refresh("financial");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            List<String> progressLines = progressLogMessages();
            assertThat(progressLines).isNotEmpty();
            assertThat(progressLines.get(0)).contains("dataset=financial").contains("rowsProcessed=");
        }
    }

    @Test
    void thresholdsFarAboveTotalRowsProduceNoProgressLogButFinalSummaryIsStillAccurate() {
        // Row/time thresholds both far larger than this tiny load will ever reach - no periodic
        // progress line should fire, but the final completed summary must still report every row.
        DataCacheProperties properties = baseProperties(1_000_000_000L, Duration.ofHours(1));
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                InMemoryDremioSource source = new InMemoryDremioSource(500, 100)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(new DataCacheMetrics(new SimpleMeterRegistry()));
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager, progressRegistry);

            DatasetRefreshResult result = coordinator.refresh("financial");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(progressLogMessages()).isEmpty(); // no periodic threshold was ever crossed

            // The final summary is unconditional - never gated by the progress-logging threshold.
            assertThat(result.timings().rowsLoaded()).isEqualTo(500);
            RefreshProgress progress = progressRegistry.find("financial").orElseThrow();
            assertThat(progress.rowsProcessed()).isEqualTo(500);
        }
    }

    @Test
    void progressLoggingCanBeDisabledEntirely() {
        DataCacheProperties properties = baseProperties(1, Duration.ofNanos(1)); // thresholds that would always fire...
        properties.getLogging().getProgress().setEnabled(false); // ...except logging is off
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                InMemoryDremioSource source = new InMemoryDremioSource(2_000, 200)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(new DataCacheMetrics(new SimpleMeterRegistry()));
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager, progressRegistry);

            DatasetRefreshResult result = coordinator.refresh("financial");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(progressLogMessages()).isEmpty();
        }
    }

    @Test
    void midStreamFailureLeavesAnAccuratePartialRowsProcessedCountBehind() {
        DataCacheProperties properties = baseProperties(1_000_000, Duration.ofSeconds(30));
        DremioSourceException failure = new DremioSourceException("simulated mid-stream connection drop", false);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                PartialStreamThenFailDremioSource source = new PartialStreamThenFailDremioSource(3_000, 500, failure)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(new DataCacheMetrics(new SimpleMeterRegistry()));
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager, progressRegistry);

            DatasetRefreshResult result = coordinator.refresh("financial");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.FAILED);
            assertThat(versionManager.findActive("financial")).isEmpty(); // never activated

            RefreshProgress progress = progressRegistry.find("financial").orElseThrow();
            assertThat(progress.rowsProcessed()).isEqualTo(3_000); // the rows that WERE successfully written
            assertThat(progress.rowsProcessed()).isGreaterThan(0);

            List<String> failureLines = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=dataset-refresh-failed"))
                    .toList();
            assertThat(failureLines).hasSize(1);
            assertThat(failureLines.get(0)).contains("rowsProcessed=3000").contains("existingActivePreserved=true");
        }
    }
}

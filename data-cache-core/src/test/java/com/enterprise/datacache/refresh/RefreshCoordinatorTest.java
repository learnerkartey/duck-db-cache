package com.enterprise.datacache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.config.RetryProperties;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.exception.DremioSourceException;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.metrics.DataCacheMetrics;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.model.VersionState;
import com.enterprise.datacache.spi.DremioSource;
import com.enterprise.datacache.testsupport.BlockingDremioSource;
import com.enterprise.datacache.testsupport.CountingFailureDremioSource;
import com.enterprise.datacache.testsupport.InMemoryDremioSource;
import com.enterprise.datacache.testsupport.StaticFailureDremioSource;
import com.enterprise.datacache.util.SqlResourceLoader;
import com.enterprise.datacache.validation.DatasetValidationService;
import com.enterprise.datacache.version.VersionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshCoordinatorTest {

    @TempDir
    Path tempDir;

    private final RetrySleeper noOpSleeper = duration -> { /* no real sleeping in tests */ };

    private DataCacheProperties baseProperties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());

        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName("widgets");
        dataset.setSourceSql("classpath:testdata/test-dataset.sql");
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(3);
        retry.setInitialDelay(Duration.ofMillis(1));
        retry.setMultiplier(2.0);
        retry.setMaxDelay(Duration.ofMillis(5));
        dataset.setRetry(retry);
        properties.getDatasets().put("widgets", dataset);
        return properties;
    }

    private RefreshCoordinator newCoordinator(DataCacheProperties properties, DremioSource source,
            MetadataStore metadataStore, VersionManager versionManager) {
        return new RefreshCoordinator(properties, source, new SqlResourceLoader(), metadataStore, versionManager,
                new DatasetValidationService(new SqlResourceLoader()), new RefreshLock(),
                new RetryExecutor(noOpSleeper), new DataCacheMetrics(new SimpleMeterRegistry()));
    }

    private MetadataStore newMetadataStore(DuckDbProperties duckDbProps) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
    }

    @Test
    void successfulRefreshActivatesFirstVersion() {
        DataCacheProperties properties = baseProperties();
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                InMemoryDremioSource source = new InMemoryDremioSource(1000, 250)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager);

            DatasetRefreshResult result = coordinator.refresh("widgets");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(result.newVersion()).isEqualTo(1L);
            assertThat(result.timings().rowsLoaded()).isEqualTo(1000);
            assertThat(result.timings().totalMs()).isGreaterThanOrEqualTo(0);
            assertThat(versionManager.findActive("widgets").orElseThrow().state()).isEqualTo(VersionState.ACTIVE);
        }
    }

    @Test
    void failedRefreshDoesNotReplaceActiveVersion() {
        DataCacheProperties properties = baseProperties();
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());

            try (InMemoryDremioSource goodSource = new InMemoryDremioSource(500, 100)) {
                RefreshCoordinator firstCoordinator = newCoordinator(properties, goodSource, metadataStore, versionManager);
                DatasetRefreshResult first = firstCoordinator.refresh("widgets");
                assertThat(first.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            }

            // A non-retryable failure classification (e.g. bad SQL) should never be retried and must
            // leave the currently ACTIVE version untouched.
            DremioSourceException nonRetryable = new DremioSourceException("simulated invalid SQL", false);
            try (DremioSource failingSource = new StaticFailureDremioSource(nonRetryable)) {
                RefreshCoordinator secondCoordinator = newCoordinator(properties, failingSource, metadataStore, versionManager);
                DatasetRefreshResult second = secondCoordinator.refresh("widgets");

                assertThat(second.outcome()).isEqualTo(RefreshOutcome.FAILED);
                assertThat(second.attemptsMade()).isEqualTo(1); // non-retryable: exactly one attempt
                assertThat(second.activeVersion()).isEqualTo(1L); // untouched
            }

            assertThat(versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
            assertThat(metadataStore.findByState("widgets", VersionState.FAILED)).hasSize(1);
        }
    }

    @Test
    void retryableFailureIsRetriedAccordingToPolicyThenGivesUp() {
        DataCacheProperties properties = baseProperties();
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            DremioSourceException retryable = new DremioSourceException("simulated transient network error", true);
            AtomicInteger attempts = new AtomicInteger();
            try (DremioSource flakySource = new CountingFailureDremioSource(retryable, attempts)) {
                RefreshCoordinator coordinator = newCoordinator(properties, flakySource, metadataStore, versionManager);
                DatasetRefreshResult result = coordinator.refresh("widgets");

                assertThat(result.outcome()).isEqualTo(RefreshOutcome.FAILED);
                assertThat(result.attemptsMade()).isEqualTo(3); // dataset configured max-attempts=3
                assertThat(attempts.get()).isEqualTo(3);
            }
        }
    }

    @Test
    void validationFailureDoesNotActivateNewVersion() {
        DataCacheProperties properties = baseProperties();
        properties.getDatasets().get("widgets").getValidation().setMinimumRowCount(1_000_000);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                InMemoryDremioSource source = new InMemoryDremioSource(100, 50)) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager);

            DatasetRefreshResult result = coordinator.refresh("widgets");

            assertThat(result.outcome()).isEqualTo(RefreshOutcome.VALIDATION_FAILED);
            assertThat(versionManager.findActive("widgets")).isEmpty();
            assertThat(metadataStore.findAll("widgets")).hasSize(1);
            assertThat(metadataStore.findAll("widgets").get(0).state()).isEqualTo(VersionState.FAILED);
        }
    }

    @Test
    void concurrentRefreshOfSameDatasetIsRejectedAsAlreadyRunning() throws Exception {
        DataCacheProperties properties = baseProperties();
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            CountDownLatch releaseFirstRefresh = new CountDownLatch(1);
            CountDownLatch firstRefreshStarted = new CountDownLatch(1);
            DremioSource blockingSource = new BlockingDremioSource(firstRefreshStarted, releaseFirstRefresh);
            RefreshCoordinator coordinator = newCoordinator(properties, blockingSource, metadataStore, versionManager);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> coordinator.refresh("widgets"));
                assertThat(firstRefreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

                DatasetRefreshResult concurrentAttempt = coordinator.refresh("widgets");
                assertThat(concurrentAttempt.outcome()).isEqualTo(RefreshOutcome.ALREADY_RUNNING);

                releaseFirstRefresh.countDown();
                DatasetRefreshResult firstResult = future.get(5, TimeUnit.SECONDS);
                assertThat(firstResult.outcome()).isEqualTo(RefreshOutcome.FAILED); // blocking source throws after release
            } finally {
                executor.shutdown();
            }
        }
    }

}

package com.enterprise.datacache.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.config.QueryProperties;
import com.enterprise.datacache.config.RetryProperties;
import com.enterprise.datacache.config.StartupExecutionMode;
import com.enterprise.datacache.config.StartupMode;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.exception.DatasetNotAvailableException;
import com.enterprise.datacache.exception.DremioSourceException;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.metrics.DataCacheMetrics;
import com.enterprise.datacache.model.DatasetAvailability;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.model.VersionState;
import com.enterprise.datacache.query.DuckDbQueryEngine;
import com.enterprise.datacache.query.QueryRegistry;
import com.enterprise.datacache.spi.ArrowBatchStream;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the mandatory startup cache lifecycle scenarios end-to-end against real DuckDB files and
 * a real {@link MetadataStore}, driving {@link DataCacheStartupCoordinator} exactly as
 * {@code DataCacheAutoConfiguration} does - through {@link DataCacheRefreshService}, never a
 * bespoke loading path - so behavior is identical to a scheduled or manual refresh.
 */
class DataCacheStartupCoordinatorTest {

    @TempDir
    Path tempDir;

    private final RetrySleeper noOpSleeper = duration -> { /* no real sleeping in tests */ };

    private DataCacheProperties baseProperties(StartupMode mode) {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());

        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName("widgets");
        dataset.setSourceSql("classpath:testdata/test-dataset.sql");
        dataset.getStartup().setMode(mode);
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(1); // these tests assert on the first attempt's outcome directly
        retry.setInitialDelay(Duration.ofMillis(1));
        dataset.setRetry(retry);
        properties.getDatasets().put("widgets", dataset);

        QueryProperties query = new QueryProperties();
        query.setSql("classpath:testdata/single-dataset-query.sql");
        query.setDatasets(List.of("widgets"));
        properties.getQueries().put("list-widgets", query);
        return properties;
    }

    /** Wires one full, independent instance of the refresh/version/startup stack against shared on-disk storage. */
    private final class Harness implements AutoCloseable {
        final DataCacheProperties properties;
        final MetadataStore metadataStore;
        final VersionManager versionManager;
        final RefreshLock refreshLock;
        final DataCacheRefreshServiceImpl refreshService;
        final DataCacheStartupCoordinator coordinator;

        Harness(DataCacheProperties properties, DremioSource source) {
            this.properties = properties;
            this.metadataStore = new MetadataStore(DuckDbConnectionFactory.open(
                    MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()), properties.getDuckdb()));
            this.versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            this.refreshLock = new RefreshLock();
            RefreshCoordinator refreshCoordinator = new RefreshCoordinator(properties, source, new SqlResourceLoader(),
                    metadataStore, versionManager, new DatasetValidationService(new SqlResourceLoader()), refreshLock,
                    new RetryExecutor(noOpSleeper), new DataCacheMetrics(new SimpleMeterRegistry()));
            this.refreshService = new DataCacheRefreshServiceImpl(refreshCoordinator, properties);
            this.coordinator = new DataCacheStartupCoordinator(properties, versionManager, refreshService);
        }

        DuckDbQueryEngine queryEngine() {
            QueryRegistry registry = new QueryRegistry(properties, new SqlResourceLoader());
            return new DuckDbQueryEngine(registry, versionManager, refreshLock, properties.getDuckdb(), properties.getPagination());
        }

        @Override
        public void close() {
            refreshService.shutdown();
            metadataStore.close();
        }
    }

    // --- Test 1: first startup, empty cache -----------------------------------------------------

    @Test
    void emptyCacheAutomaticallyCreatesFirstVersionOnStartupWithNoManualTrigger() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        try (InMemoryDremioSource source = new InMemoryDremioSource(500, 100);
                Harness h = new Harness(properties, source)) {
            assertThat(h.versionManager.findActive("widgets")).isEmpty();

            h.coordinator.runStartupSequence(); // no manual refresh call anywhere in this test

            await().atMost(Duration.ofSeconds(10)).until(() -> h.versionManager.findActive("widgets").isPresent());
            DatasetVersion active = h.versionManager.findActive("widgets").orElseThrow();
            assertThat(active.version()).isEqualTo(1L);
            assertThat(active.state()).isEqualTo(VersionState.ACTIVE);
            assertThat(active.filePath()).exists();
        }
    }

    // --- Test 2: existing cache + USE_EXISTING_OR_CREATE -----------------------------------------

    @Test
    void existingActiveCacheIsReusedWithoutStartupRefreshInUseExistingOrCreateMode() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        try (InMemoryDremioSource seedSource = new InMemoryDremioSource(200, 50);
                Harness seeder = new Harness(properties, seedSource)) {
            DatasetRefreshResult seeded = seeder.refreshService.refresh("widgets");
            assertThat(seeded.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(seeder.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
        }

        AtomicInteger callsToSource = new AtomicInteger();
        CountingFailureDremioSource mustNeverBeCalled = new CountingFailureDremioSource(
                new DremioSourceException("must not be called - USE_EXISTING_OR_CREATE must not refresh an existing cache", false),
                callsToSource);
        try (Harness h = new Harness(properties, mustNeverBeCalled)) {
            h.coordinator.runStartupSequence();

            // The decision not to trigger a refresh is made synchronously inside runStartupSequence()
            // for a dataset that already has an ACTIVE version and is not ALWAYS_REFRESH - no
            // asynchronous work is queued at all, so this can be asserted immediately.
            assertThat(callsToSource.get()).isZero();
            assertThat(h.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
            assertThat(h.metadataStore.findAll("widgets")).hasSize(1);
        }
    }

    // --- Test 3: existing cache + ALWAYS_REFRESH --------------------------------------------------

    @Test
    void alwaysRefreshKeepsExistingActiveImmediatelyAvailableWhileBuildingNewVersionInBackground() throws Exception {
        DataCacheProperties properties = baseProperties(StartupMode.ALWAYS_REFRESH);
        try (InMemoryDremioSource seedSource = new InMemoryDremioSource(300, 100);
                Harness seeder = new Harness(properties, seedSource)) {
            seeder.refreshService.refresh("widgets");
            assertThat(seeder.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
        }

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (InMemoryDremioSource delegate = new InMemoryDremioSource(300, 100)) {
            PausingDremioSource pausing = new PausingDremioSource(started, release, delegate);
            try (Harness h = new Harness(properties, pausing)) {
                h.coordinator.runStartupSequence();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                // Mid-background-refresh: the existing ACTIVE version is still exactly what queries see.
                assertThat(h.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
                DuckDbQueryEngine engine = h.queryEngine();
                assertThat(engine.execute("list-widgets", Map.of("excluded", "none"), 0, 10).datasetVersionsUsed())
                        .containsEntry("widgets", 1L);

                release.countDown();

                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(h.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(2L));
                assertThat(h.metadataStore.findPrevious("widgets")).extracting(DatasetVersion::version).containsExactly(1L);
            }
        }
    }

    // --- Test 4: ALWAYS_REFRESH startup failure with existing cache -------------------------------

    @Test
    void alwaysRefreshStartupFailureLeavesExistingActiveCacheIntact() {
        DataCacheProperties properties = baseProperties(StartupMode.ALWAYS_REFRESH);
        try (InMemoryDremioSource seedSource = new InMemoryDremioSource(150, 50);
                Harness seeder = new Harness(properties, seedSource)) {
            seeder.refreshService.refresh("widgets");
        }

        DremioSourceException nonRetryable = new DremioSourceException("simulated startup refresh failure", false);
        try (Harness h = new Harness(properties, new StaticFailureDremioSource(nonRetryable))) {
            h.coordinator.runStartupSequence();

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !h.metadataStore.findByState("widgets", VersionState.FAILED).isEmpty());

            assertThat(h.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
            DuckDbQueryEngine engine = h.queryEngine();
            assertThat(engine.execute("list-widgets", Map.of("excluded", "none"), 0, 10).datasetVersionsUsed())
                    .containsEntry("widgets", 1L);
        }
    }

    // --- Test 5: first startup load failure --------------------------------------------------------

    @Test
    void firstStartupLoadFailureLeavesDatasetCleanlyUnavailable() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        DremioSourceException nonRetryable = new DremioSourceException("simulated first-load failure", false);
        try (Harness h = new Harness(properties, new StaticFailureDremioSource(nonRetryable))) {
            h.coordinator.runStartupSequence();

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !h.metadataStore.findByState("widgets", VersionState.FAILED).isEmpty());
            assertThat(h.versionManager.findActive("widgets")).isEmpty();

            DuckDbQueryEngine engine = h.queryEngine();
            assertThatThrownBy(() -> engine.execute("list-widgets", Map.of("excluded", "none"), 0, 10))
                    .isInstanceOf(DatasetNotAvailableException.class)
                    .satisfies(e -> assertThat(((DatasetNotAvailableException) e).availability())
                            .isEqualTo(DatasetAvailability.UNAVAILABLE));
        }
    }

    // --- Test 6: restart after successful cache (two independent process instances) ----------------

    @Test
    void restartAfterSuccessfulCacheReusesActiveVersionWithoutReloading() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);

        // "Instance 1": empty cache, mandatory auto-create runs, then the process stops.
        try (InMemoryDremioSource source = new InMemoryDremioSource(300, 100);
                Harness instance1 = new Harness(properties, source)) {
            instance1.coordinator.runStartupSequence();
            await().atMost(Duration.ofSeconds(10)).until(() -> instance1.versionManager.findActive("widgets").isPresent());
            assertThat(instance1.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
        }

        // "Instance 2": fresh process, same on-disk storage, must never touch Dremio again.
        AtomicInteger callsToSource = new AtomicInteger();
        CountingFailureDremioSource mustNeverBeCalled = new CountingFailureDremioSource(
                new DremioSourceException("must not be called on restart", false), callsToSource);
        try (Harness instance2 = new Harness(properties, mustNeverBeCalled)) {
            instance2.coordinator.runStartupSequence();

            assertThat(instance2.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
            assertThat(callsToSource.get()).isZero();
        }
    }

    // --- Test 7: startup refresh vs. concurrent "scheduler" refresh race ----------------------------

    @Test
    void startupTriggeredRefreshAndConcurrentSchedulerTriggeredRefreshNeverRunSimultaneously() throws Exception {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Harness h = new Harness(properties, new BlockingDremioSource(started, release))) {
            h.coordinator.runStartupSequence(); // no ACTIVE version yet -> mandatory auto-create, async
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // startup refresh now holds the RefreshLock

            // A cron-scheduled refresh firing for the same dataset at (almost) the same moment.
            DatasetRefreshResult schedulerAttempt = h.refreshService.refresh("widgets");
            assertThat(schedulerAttempt.outcome()).isEqualTo(RefreshOutcome.ALREADY_RUNNING);

            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> !h.refreshLock.isRunning("widgets"));
            // The startup attempt itself fails once released (BlockingDremioSource throws after
            // release), which is irrelevant here - what matters is that only one refresh ever ran.
        }
    }

    // --- BLOCK_UNTIL_REQUIRED_CACHE_READY execution mode -------------------------------------------

    @Test
    void blockingExecutionModeWaitsForRequiredDatasetBeforeReturning() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        properties.getStartup().setTimeout(Duration.ofSeconds(30));
        properties.getDatasets().get("widgets").setRequired(true);

        try (InMemoryDremioSource source = new InMemoryDremioSource(400, 100);
                Harness h = new Harness(properties, source)) {
            h.coordinator.runStartupSequence(); // must not return until the required load has finished

            assertThat(h.versionManager.findActive("widgets")).isPresent();
            assertThat(h.versionManager.findActive("widgets").orElseThrow().version()).isEqualTo(1L);
        }
    }

    @Test
    void blockingExecutionModeGivesUpAfterTimeoutWithoutHangingAndKeepsLoadingInBackground() throws Exception {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        properties.getStartup().setTimeout(Duration.ofMillis(300));
        properties.getDatasets().get("widgets").setRequired(true);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (InMemoryDremioSource delegate = new InMemoryDremioSource(200, 50);
                Harness h = new Harness(properties, new PausingDremioSource(started, release, delegate))) {
            long startNanos = System.nanoTime();
            h.coordinator.runStartupSequence(); // load pauses well past the 300ms timeout
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            // Must return promptly once the timeout elapses - never hang indefinitely.
            assertThat(elapsedMs).isLessThan(5_000L);
            assertThat(h.versionManager.findActive("widgets")).isEmpty(); // still loading, not yet ready

            // Background loading continues after the coordinator gave up waiting.
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> h.versionManager.findActive("widgets").isPresent());
        }
    }

    @Test
    void blockingExecutionModeDoesNotWaitForNonRequiredDataset() {
        DataCacheProperties properties = baseProperties(StartupMode.USE_EXISTING_OR_CREATE);
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        properties.getStartup().setTimeout(Duration.ofSeconds(30));
        properties.getDatasets().get("widgets").setRequired(false);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (InMemoryDremioSource delegate = new InMemoryDremioSource(200, 50);
                Harness h = new Harness(properties, new PausingDremioSource(started, release, delegate))) {
            long startNanos = System.nanoTime();
            h.coordinator.runStartupSequence(); // widgets is not required, so this must return immediately
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertThat(elapsedMs).isLessThan(2_000L);
            assertThat(h.versionManager.findActive("widgets")).isEmpty(); // still loading in the background

            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> h.versionManager.findActive("widgets").isPresent());
        }
    }

    /** Signals {@code started}, waits for {@code release}, then delegates to a real source - used to hold a refresh "in flight" without failing it. */
    private record PausingDremioSource(CountDownLatch started, CountDownLatch release, DremioSource delegate) implements DremioSource {
        @Override
        public ArrowBatchStream executeQuery(String sql) {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.executeQuery(sql);
        }

        @Override
        public boolean isHealthy() {
            return delegate.isHealthy();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}

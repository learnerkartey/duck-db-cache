package com.enterprise.datacache.feature.cache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.config.QueryProperties;
import com.enterprise.datacache.feature.cache.config.RetryProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.ConsistencyMode;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.PagedQueryResult;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import com.enterprise.datacache.feature.cache.model.ResumeStrategyType;
import com.enterprise.datacache.feature.cache.query.DuckDbQueryEngine;
import com.enterprise.datacache.feature.cache.query.QueryRegistry;
import com.enterprise.datacache.feature.cache.resume.ResumableRefreshExecutor;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import com.enterprise.datacache.feature.cache.testsupport.ChunkAwareDremioSource;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two mandatory resumable-refresh tests that need the full stack (query engine, version
 * manager, multi-dataset concurrency) rather than {@link ResumableRefreshExecutor} in isolation -
 * see {@code ResumableRefreshExecutorTest} for the chunk-level crash/idempotency tests.
 */
class ResumableRefreshEndToEndTest {

    @TempDir
    Path tempDir;

    private final RetrySleeper noOpSleeper = duration -> { /* no real sleeping in tests */ };

    private MetadataStore newMetadataStore(DuckDbProperties duckDbProps) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
    }

    private DatasetProperties resumableDataset(String tableName) {
        return resumableDataset(tableName, "classpath:testdata/test-dataset.sql");
    }

    private DatasetProperties resumableDataset(String tableName, String sourceSql) {
        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName(tableName);
        dataset.setSourceSql(sourceSql);
        DatasetResumeProperties resume = dataset.getResume();
        resume.setEnabled(true);
        resume.setStrategy(ResumeStrategyType.RANGE);
        resume.setPartitionColumn("id");
        resume.setChunkSize(100);
        resume.setConsistency(ConsistencyMode.BEST_EFFORT);
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(1); // a single simulated failure must not be auto-retried away within one refresh() call
        retry.setInitialDelay(Duration.ofMillis(1));
        dataset.setRetry(retry);
        return dataset;
    }

    private RefreshCoordinator newCoordinator(DataCacheProperties properties, DremioSource source, MetadataStore metadataStore,
            VersionManager versionManager) {
        DataCacheMetrics metrics = new DataCacheMetrics(new SimpleMeterRegistry());
        return new RefreshCoordinator(properties, source, new SqlResourceLoader(), metadataStore, versionManager,
                new com.enterprise.datacache.feature.cache.validation.DatasetValidationService(new SqlResourceLoader()),
                new RefreshLock(), new RetryExecutor(noOpSleeper), metrics, new RefreshProgressRegistry(metrics),
                new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore));
    }

    // --- 7: ACTIVE stays available and correct throughout a paused-then-resumed BUILDING version ----

    @Test
    void activeVersionKeepsServingQueriesWhilePausedThenSwitchesOnlyOnceResumeCompletes() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getResume().setEnabled(true);
        properties.getDatasets().put("financial", resumableDataset("financial"));

        QueryProperties countQuery = new QueryProperties();
        countQuery.setSql("classpath:testdata/financial-count-query.sql");
        countQuery.setDatasets(List.of("financial"));
        properties.getQueries().put("financial-count", countQuery);

        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            QueryRegistry queryRegistry = new QueryRegistry(properties, new SqlResourceLoader());
            DuckDbQueryEngine queryEngine = new DuckDbQueryEngine(queryRegistry, versionManager, new RefreshLock(),
                    properties.getDuckdb(), properties.getPagination());

            // V1: an uninterrupted resumable load - becomes ACTIVE normally.
            try (ChunkAwareDremioSource sourceV1 = new ChunkAwareDremioSource(300, 40)) {
                DatasetRefreshResult r1 = newCoordinator(properties, sourceV1, metadataStore, versionManager).refresh("financial");
                assertThat(r1.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            }
            assertThat(versionManager.findActive("financial").orElseThrow().version()).isEqualTo(1L);
            assertRowCountServedBy(queryEngine, 1L, 300L);

            // V2: a resumable load that hits a simulated crash partway through and stays paused/BUILDING.
            try (ChunkAwareDremioSource sourceV2 = new ChunkAwareDremioSource(1200, 100)) {
                sourceV2.failChunkStartingAt(401, 0); // chunk 5 of 12
                RefreshCoordinator coordinator2 = newCoordinator(properties, sourceV2, metadataStore, versionManager);

                DatasetRefreshResult r2 = coordinator2.refresh("financial");
                assertThat(r2.outcome()).isEqualTo(RefreshOutcome.FAILED);
                assertThat(r2.activeVersion()).isEqualTo(1L); // ACTIVE untouched by the paused build

                // While V2 sits paused, every query must still see V1 - never the incomplete V2.
                assertThat(versionManager.findActive("financial").orElseThrow().version()).isEqualTo(1L);
                assertRowCountServedBy(queryEngine, 1L, 300L);

                // Resume: a fresh refresh() call picks up exactly where V2 left off (chunk 5's one-shot
                // failure was already consumed, so this attempt completes chunks 5-12 and activates).
                DatasetRefreshResult r3 = coordinator2.refresh("financial");
                assertThat(r3.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
                assertThat(r3.newVersion()).isEqualTo(2L);
            }

            assertThat(versionManager.findActive("financial").orElseThrow().version()).isEqualTo(2L);
            assertRowCountServedBy(queryEngine, 2L, 1200L);
        }
    }

    private void assertRowCountServedBy(DuckDbQueryEngine queryEngine, long expectedVersion, long expectedRowCount) {
        PagedQueryResult result = queryEngine.execute("financial-count", Map.of(), 0, 10);
        assertThat(result.datasetVersionsUsed().get("financial")).isEqualTo(expectedVersion);
        assertThat(result.rows().get(0).get("row_count")).isEqualTo(expectedRowCount);
    }

    // --- 8: multiple datasets resume independently, respecting max-concurrent-datasets -------------

    @Test
    void multipleDatasetsResumeIndependentlyRespectingConcurrencyLimit() throws Exception {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getResume().setEnabled(true);
        properties.getRefresh().setMaxConcurrentDatasets(1);
        properties.getDatasets().put("financial", resumableDataset("financial", "classpath:testdata/financial-source.sql"));
        properties.getDatasets().put("organization", resumableDataset("organization", "classpath:testdata/organization-source.sql"));

        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            // One shared source plays both datasets' Dremio endpoint, exactly like production where a
            // single DremioSource serves every dataset's queries - each dataset's distinguishable
            // source-sql content lets the one shared source return a different row count per dataset.
            try (ChunkAwareDremioSource source = new ChunkAwareDremioSource(1, 1)) {
                source.setTotalRowsForMarker("test_source_financial", 300);
                source.setTotalRowsForMarker("test_source_organization", 250);
                source.simulateLatency(true);
                RefreshCoordinator coordinator = newCoordinator(properties, source, metadataStore, versionManager);
                DataCacheRefreshServiceImpl refreshService = new DataCacheRefreshServiceImpl(coordinator, properties);
                try {
                    // Pre-seed both datasets with a paused partial build: financial fails its chunk
                    // starting at 201 (of 300 rows / chunkSize 100), organization fails its chunk
                    // starting at 126 (of 250 rows / chunkSize 125) - distinct chunk-start values so
                    // arming one failure never risks consuming the other's.
                    properties.getDatasets().get("financial").getResume().setChunkSize(100);
                    properties.getDatasets().get("organization").getResume().setChunkSize(125);
                    source.failChunkStartingAt(201, 0);
                    assertThat(refreshService.refresh("financial", com.enterprise.datacache.feature.cache.model.RefreshTrigger.MANUAL)
                            .outcome()).isEqualTo(RefreshOutcome.FAILED);
                    source.failChunkStartingAt(126, 0);
                    assertThat(refreshService.refresh("organization", com.enterprise.datacache.feature.cache.model.RefreshTrigger.MANUAL)
                            .outcome()).isEqualTo(RefreshOutcome.FAILED);

                    assertThat(metadataStore.findBuildingManifest("financial")).isPresent();
                    assertThat(metadataStore.findBuildingManifest("organization")).isPresent();
                    assertThat(versionManager.findActive("financial")).isEmpty();
                    assertThat(versionManager.findActive("organization")).isEmpty();

                    // Restart: resume both concurrently, respecting max-concurrent-datasets=1.
                    CompletableFuture<DatasetRefreshResult> financialResume =
                            refreshService.refreshAsync("financial", com.enterprise.datacache.feature.cache.model.RefreshTrigger.STARTUP);
                    CompletableFuture<DatasetRefreshResult> organizationResume =
                            refreshService.refreshAsync("organization", com.enterprise.datacache.feature.cache.model.RefreshTrigger.STARTUP);

                    DatasetRefreshResult financialResult = financialResume.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    DatasetRefreshResult organizationResult = organizationResume.get(30, java.util.concurrent.TimeUnit.SECONDS);

                    assertThat(financialResult.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
                    assertThat(organizationResult.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
                    assertThat(financialResult.timings().rowsLoaded()).isEqualTo(300);
                    assertThat(organizationResult.timings().rowsLoaded()).isEqualTo(250);
                    assertThat(source.maxObservedConcurrentChunkQueries())
                            .as("data-cache.refresh.max-concurrent-datasets=1 must bound cross-dataset concurrency")
                            .isLessThanOrEqualTo(1);

                    assertThat(versionManager.findActive("financial").orElseThrow().rowCount()).isEqualTo(300L);
                    assertThat(versionManager.findActive("organization").orElseThrow().rowCount()).isEqualTo(250L);
                } finally {
                    refreshService.shutdown();
                }
            }
        }
    }
}

package com.enterprise.datacache.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.enterprise.datacache.health.DataCacheStatusService;
import com.enterprise.datacache.model.DatasetAvailability;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.DatasetStatus;
import com.enterprise.datacache.model.PagedQueryResult;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.query.DataCacheQueryService;
import com.enterprise.datacache.refresh.DataCacheRefreshService;
import com.enterprise.datacache.spi.DremioSource;
import com.enterprise.datacache.testsupport.InMemoryDremioSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves that {@code data-cache-core} is genuinely portable: this test boots a completely minimal
 * {@code @SpringBootApplication} that depends on NOTHING but {@code data-cache-core} (this module's
 * own test classpath never includes {@code data-cache-app} - Gradle module dependencies only flow
 * the other direction), replaces only the external Dremio boundary with a test double, and
 * exercises the real production DuckDB writer, metadata store, refresh coordinator, version
 * manager, query engine, and - critically - the mandatory startup cache lifecycle end-to-end via
 * {@link DataCacheAutoConfiguration}: with no {@code data-cache.datasets.widgets.startup.mode}
 * configured (the {@code USE_EXISTING_OR_CREATE} default) and no manual refresh call, the
 * dataset must load itself automatically on startup.
 */
@SpringBootTest(classes = EmbeddingAcceptanceTest.MinimalTestApplication.class,
        properties = {
                "data-cache.enabled=true",
                "data-cache.datasets.widgets.table-name=widgets",
                "data-cache.datasets.widgets.source-sql=classpath:testdata/test-dataset.sql",
                "data-cache.queries.list-widgets.sql=classpath:testdata/single-dataset-query.sql",
                "data-cache.queries.list-widgets.datasets[0]=widgets"
        })
@Import(EmbeddingAcceptanceTest.TestDremioSourceConfiguration.class)
class EmbeddingAcceptanceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerCacheStorage(DynamicPropertyRegistry registry) {
        registry.add("data-cache.duckdb.base-directory", () -> tempDir.resolve("cache").toString());
        registry.add("data-cache.duckdb.temp-directory", () -> tempDir.resolve("temp").toString());
    }

    @SpringBootApplication
    static class MinimalTestApplication {
    }

    /** Spring Boot Test auto-detects and applies static nested {@code @TestConfiguration} classes. */
    @TestConfiguration
    static class TestDremioSourceConfiguration {
        @Bean
        DremioSource dremioSource() {
            return new InMemoryDremioSource(2_000, 400);
        }
    }

    @Autowired
    private DataCacheQueryService queryService;

    @Autowired
    private DataCacheRefreshService refreshService;

    @Autowired
    private DataCacheStatusService statusService;

    @Test
    void springBootAutoConfiguresCoreServicesAndFullStartupThenRefreshQueryLifecycleWorks() {
        assertThat(queryService).isNotNull();
        assertThat(refreshService).isNotNull();
        assertThat(statusService).isNotNull();

        // No manual refresh call anywhere above this line: the dataset has no ACTIVE cache when the
        // context starts, so DataCacheStartupCoordinator must load it automatically (default
        // StartupMode.USE_EXISTING_OR_CREATE's mandatory auto-create rule).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(statusService.getStatus("widgets").availability()).isEqualTo(DatasetAvailability.AVAILABLE));

        DatasetStatus statusAfterAutoCreate = statusService.getStatus("widgets");
        assertThat(statusAfterAutoCreate.lastRefreshStatus()).isEqualTo(RefreshOutcome.SUCCESS);
        assertThat(statusAfterAutoCreate.activeRowCount()).isEqualTo(2000L);
        long autoCreatedVersion = statusAfterAutoCreate.activeVersion();

        PagedQueryResult result = queryService.execute("list-widgets", Map.of("excluded", "no-match"), 0, 50);
        assertThat(result.rows()).hasSize(50);
        assertThat(result.totalRows()).isEqualTo(2000);
        assertThat(result.datasetVersionsUsed()).containsEntry("widgets", autoCreatedVersion);

        // A manual refresh still goes through the exact same pipeline and produces the next version.
        DatasetRefreshResult manual = refreshService.refresh("widgets");
        assertThat(manual.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
        assertThat(manual.newVersion()).isEqualTo(autoCreatedVersion + 1);

        DatasetStatus statusAfterManual = statusService.getStatus("widgets");
        assertThat(statusAfterManual.activeVersion()).isEqualTo(autoCreatedVersion + 1);
        assertThat(statusAfterManual.previousVersion()).isEqualTo(autoCreatedVersion);

        PagedQueryResult resultAfterManualRefresh = queryService.execute("list-widgets", Map.of("excluded", "no-match"), 0, 10);
        assertThat(resultAfterManualRefresh.datasetVersionsUsed()).containsEntry("widgets", autoCreatedVersion + 1);
    }
}

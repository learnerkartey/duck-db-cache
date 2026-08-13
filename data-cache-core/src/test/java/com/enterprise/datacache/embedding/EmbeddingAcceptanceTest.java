package com.enterprise.datacache.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.health.DataCacheStatusService;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.DatasetStatus;
import com.enterprise.datacache.model.PagedQueryResult;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.query.DataCacheQueryService;
import com.enterprise.datacache.refresh.DataCacheRefreshService;
import com.enterprise.datacache.spi.DremioSource;
import com.enterprise.datacache.testsupport.InMemoryDremioSource;
import java.nio.file.Path;
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
 * manager, and query engine end-to-end via {@link DataCacheAutoConfiguration}.
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
    void springBootAutoConfiguresCoreServicesAndFullRefreshQueryLifecycleWorks() {
        assertThat(queryService).isNotNull();
        assertThat(refreshService).isNotNull();
        assertThat(statusService).isNotNull();

        DatasetRefreshResult first = refreshService.refresh("widgets");
        assertThat(first.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
        assertThat(first.newVersion()).isEqualTo(1L);

        DatasetStatus statusAfterFirst = statusService.getStatus("widgets");
        assertThat(statusAfterFirst.activeVersion()).isEqualTo(1L);
        assertThat(statusAfterFirst.activeRowCount()).isEqualTo(2000L);

        PagedQueryResult result = queryService.execute("list-widgets", Map.of("excluded", "no-match"), 0, 50);
        assertThat(result.rows()).hasSize(50);
        assertThat(result.totalRows()).isEqualTo(2000);

        DatasetRefreshResult second = refreshService.refresh("widgets");
        assertThat(second.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
        assertThat(second.newVersion()).isEqualTo(2L);

        DatasetStatus statusAfterSecond = statusService.getStatus("widgets");
        assertThat(statusAfterSecond.activeVersion()).isEqualTo(2L);
        assertThat(statusAfterSecond.previousVersion()).isEqualTo(1L);

        PagedQueryResult resultAfterSecondRefresh = queryService.execute("list-widgets", Map.of("excluded", "no-match"), 0, 10);
        assertThat(resultAfterSecondRefresh.datasetVersionsUsed()).containsEntry("widgets", 2L);
    }
}

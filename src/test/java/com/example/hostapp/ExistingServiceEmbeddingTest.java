package com.example.hostapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.enterprise.datacache.feature.cache.health.DataCacheStatusService;
import com.enterprise.datacache.feature.cache.query.DataCacheQueryService;
import com.enterprise.datacache.feature.cache.refresh.DataCacheRefreshService;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the entire integration story from docs/INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md
 * end to end: {@link HostApplication} is a stand-in for a completely unrelated, already-existing
 * Spring Boot application (different base package, its own beans and {@code DataSource}) that adds
 * exactly one thing - {@code @EnableDataCache} - and, without any package renaming or component
 * scanning of {@code com.enterprise.datacache}, gets a fully wired cache feature while its own
 * beans and its own {@code DataSource} are left completely untouched.
 */
@SpringBootTest(classes = HostApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExistingServiceEmbeddingTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("data-cache.duckdb.base-directory", () -> tempDir.resolve("cache").toString());
        registry.add("data-cache.duckdb.temp-directory", () -> tempDir.resolve("temp").toString());
    }

    @Autowired
    private DataCacheQueryService queryService;

    @Autowired
    private DataCacheRefreshService refreshService;

    @Autowired
    private DataCacheStatusService statusService;

    @Autowired
    private SampleHostService sampleHostService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void awaitStartupAutoLoadAttemptsToSettle() {
        for (String dataset : new String[] {"financial", "organization", "headcount"}) {
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> !statusService.getStatus(dataset).refreshInProgress());
        }
    }

    @Test
    void cacheServicesInjectIntoAnUnrelatedHostApplication() {
        assertThat(queryService).isNotNull();
        assertThat(refreshService).isNotNull();
        assertThat(statusService).isNotNull();
    }

    @Test
    void unrelatedHostBeanStillInjectsAlongsideTheCacheFeature() {
        assertThat(sampleHostService).isNotNull();
        assertThat(sampleHostService.greeting()).isEqualTo("hello from host application");
    }

    @Test
    void hostDataSourceIsNotReplacedOrShadowedByTheCacheFeature() {
        // The cache feature never registers a javax.sql.DataSource bean of its own - DuckDB access
        // is entirely internal to feature.cache.duckdb - so the host's own DataSource bean must be
        // the only one in the context, exactly as the host defined it under its own bean name.
        assertThat(applicationContext.getBeanNamesForType(DataSource.class)).containsExactly("hostDataSource");
        assertThat(applicationContext.getBean("hostDataSource")).isSameAs(dataSource);
    }
}

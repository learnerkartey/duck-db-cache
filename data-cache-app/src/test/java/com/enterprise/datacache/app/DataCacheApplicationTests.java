package com.enterprise.datacache.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.datacache.health.DataCacheStatusService;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end web-layer smoke test: the full application context (real auto-configuration, real
 * controllers) starts without a live Dremio connection - Dremio connects lazily - and the
 * admin/query REST surface behaves correctly against it, including the mandatory startup
 * auto-create attempt: with no ACTIVE cache and a blank Dremio host, every enabled dataset
 * automatically attempts (and, here, fails) an initial load on its own, with no manual trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataCacheApplicationTests {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("data-cache.duckdb.base-directory", () -> tempDir.resolve("cache").toString());
        registry.add("data-cache.duckdb.temp-directory", () -> tempDir.resolve("temp").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataCacheStatusService statusService;

    @BeforeEach
    void awaitStartupAutoLoadAttemptsToSettle() {
        // The mandatory startup auto-create rule fires an async refreshAsync() for every enabled
        // dataset with no ACTIVE version. With no live Dremio configured these fail almost
        // instantly (no network I/O - just a blank-host check), but each test still needs a
        // deterministic starting point rather than racing that background attempt.
        for (String dataset : new String[] {"financial", "organization", "headcount"}) {
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> !statusService.getStatus(dataset).refreshInProgress());
        }
    }

    @Test
    void contextLoadsWithoutLiveDremioConnection() {
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void mandatoryStartupAutoCreateAttemptedAndFailedCleanlyWithoutLiveDremio() throws Exception {
        // Proves the mandatory auto-create rule: nobody called refresh, yet every enabled dataset
        // already has a recorded (failed, since there's no real Dremio) attempt.
        mockMvc.perform(get("/api/v1/cache/admin/datasets/financial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRefreshStatus").value("FAILED"))
                .andExpect(jsonPath("$.lastError").isNotEmpty())
                .andExpect(jsonPath("$.activeVersion").doesNotExist());
    }

    @Test
    void listDatasetsReturnsConfiguredDatasets() throws Exception {
        mockMvc.perform(get("/api/v1/cache/admin/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financial.enabled").value(true))
                .andExpect(jsonPath("$.organization").exists())
                .andExpect(jsonPath("$.headcount").exists());
    }

    @Test
    void getSingleDatasetStatus() throws Exception {
        mockMvc.perform(get("/api/v1/cache/admin/datasets/financial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetName").value("financial"))
                .andExpect(jsonPath("$.refreshInProgress").value(false));
    }

    @Test
    void unknownDatasetStatusReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/cache/admin/datasets/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DATASET_NOT_FOUND"));
    }

    @Test
    void unknownQueryNameReturns404NotArbitrarySql() throws Exception {
        mockMvc.perform(post("/api/v1/cache/query/no-such-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{},\"page\":0,\"size\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("QUERY_NOT_FOUND"));
    }

    @Test
    void queryAgainstDatasetWithNoActiveCacheFailsCleanlyAsServiceUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/cache/query/financial-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{\"fiscalYear\":2026}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DATASET_NOT_AVAILABLE"));
    }

    @Test
    void refreshWithoutDremioConfiguredFailsCleanlyRatherThanCrashingTheApp() throws Exception {
        mockMvc.perform(post("/api/v1/cache/admin/datasets/financial/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("DREMIO_SOURCE_ERROR"));
    }
}

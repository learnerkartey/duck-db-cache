package com.enterprise.datacache.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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
 * controllers) starts without a live Dremio connection - Dremio connects lazily, so an application
 * with {@code load-on-startup: false} everywhere must come up cleanly - and the admin/query REST
 * surface behaves correctly against it.
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

    @Test
    void contextLoadsWithoutLiveDremioConnection() {
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void listDatasetsReturnsConfiguredDatasetsNeverRun() throws Exception {
        mockMvc.perform(get("/api/v1/cache/admin/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financial.enabled").value(true))
                .andExpect(jsonPath("$.financial.lastRefreshStatus").value("NEVER_RUN"))
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
    void refreshWithoutDremioConfiguredFailsCleanlyRatherThanCrashingTheApp() throws Exception {
        mockMvc.perform(post("/api/v1/cache/admin/datasets/financial/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("DREMIO_SOURCE_ERROR"));
    }
}

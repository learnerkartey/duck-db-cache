package com.enterprise.datacache.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.config.StartupExecutionMode;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.refresh.RefreshLock;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class DataCacheReadinessIndicatorTest {

    @TempDir
    Path tempDir;

    private DataCacheProperties properties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        return properties;
    }

    private MetadataStore metadataStore(DataCacheProperties properties) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()), properties.getDuckdb()));
    }

    @Test
    void alwaysUpInAsyncExecutionModeRegardlessOfDatasetState() {
        DataCacheProperties properties = properties();
        properties.getStartup().setExecutionMode(StartupExecutionMode.ASYNC);
        DatasetProperties dataset = new DatasetProperties();
        dataset.setRequired(true);
        properties.getDatasets().put("financial", dataset);

        try (MetadataStore metadataStore = metadataStore(properties)) {
            DataCacheStatusService statusService = new DataCacheStatusServiceImpl(properties, metadataStore, new RefreshLock());
            DataCacheReadinessIndicator indicator = new DataCacheReadinessIndicator(properties, statusService);

            Health health = indicator.health(); // "financial" has no ACTIVE version at all
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void blockingModeReportsDownWhileRequiredDatasetHasNoActiveVersion() {
        DataCacheProperties properties = properties();
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        DatasetProperties required = new DatasetProperties();
        required.setRequired(true);
        properties.getDatasets().put("financial", required);

        try (MetadataStore metadataStore = metadataStore(properties)) {
            DataCacheStatusService statusService = new DataCacheStatusServiceImpl(properties, metadataStore, new RefreshLock());
            DataCacheReadinessIndicator indicator = new DataCacheReadinessIndicator(properties, statusService);

            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    void blockingModeReportsUpOnceRequiredDatasetHasActiveVersion() {
        DataCacheProperties properties = properties();
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        DatasetProperties required = new DatasetProperties();
        required.setRequired(true);
        properties.getDatasets().put("financial", required);

        try (MetadataStore metadataStore = metadataStore(properties)) {
            metadataStore.createBuildingVersion("financial", 1, tempDir.resolve("financial_v1.duckdb"), "financial", "h1");
            metadataStore.activateVersion("financial", 1);

            DataCacheStatusService statusService = new DataCacheStatusServiceImpl(properties, metadataStore, new RefreshLock());
            DataCacheReadinessIndicator indicator = new DataCacheReadinessIndicator(properties, statusService);

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void blockingModeIgnoresNonRequiredDatasetsWithNoActiveVersion() {
        DataCacheProperties properties = properties();
        properties.getStartup().setExecutionMode(StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY);
        DatasetProperties required = new DatasetProperties();
        required.setRequired(true);
        properties.getDatasets().put("financial", required);
        DatasetProperties optional = new DatasetProperties();
        optional.setRequired(false);
        properties.getDatasets().put("headcount", optional);

        try (MetadataStore metadataStore = metadataStore(properties)) {
            metadataStore.createBuildingVersion("financial", 1, tempDir.resolve("financial_v1.duckdb"), "financial", "h1");
            metadataStore.activateVersion("financial", 1);
            // headcount never gets an ACTIVE version in this test.

            DataCacheStatusService statusService = new DataCacheStatusServiceImpl(properties, metadataStore, new RefreshLock());
            DataCacheReadinessIndicator indicator = new DataCacheReadinessIndicator(properties, statusService);

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }
    }
}

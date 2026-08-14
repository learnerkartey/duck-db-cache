package com.enterprise.datacache.feature.cache.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import com.enterprise.datacache.feature.cache.model.RefreshStage;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import com.enterprise.datacache.feature.cache.refresh.RefreshLock;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgress;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgressRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the status API's live progress fields (item 20/21 of the progress-logging spec): while a
 * dataset is refreshing, {@code activeVersion} keeps reporting the version actually serving
 * queries and {@code buildingVersion}/{@code refreshStage}/{@code rowsProcessed} report the
 * in-flight attempt separately - an operator can see both at once and understand that queries are
 * still being served by the old version while a new one builds.
 */
class DataCacheStatusServiceProgressTest {

    @TempDir
    Path tempDir;

    private DataCacheProperties properties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        DatasetProperties dataset = new DatasetProperties();
        properties.getDatasets().put("financial", dataset);
        return properties;
    }

    private MetadataStore metadataStore(DataCacheProperties properties) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()), properties.getDuckdb()));
    }

    @Test
    void statusExposesActiveAndBuildingVersionsSimultaneouslyWhileARefreshIsInFlight() {
        DataCacheProperties properties = properties();
        try (MetadataStore metadataStore = metadataStore(properties)) {
            // V12 is ACTIVE - already serving queries.
            metadataStore.createBuildingVersion("financial", 12, tempDir.resolve("financial_v12.duckdb"), "financial", "h1");
            metadataStore.activateVersion("financial", 12);

            RefreshLock refreshLock = new RefreshLock();
            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(
                    new DataCacheMetrics(new SimpleMeterRegistry()));
            DataCacheStatusServiceImpl statusService = new DataCacheStatusServiceImpl(properties, metadataStore,
                    refreshLock, progressRegistry);

            // V13 starts building in the background - lock held, progress registered - exactly as
            // RefreshCoordinator does mid-refresh.
            refreshLock.tryAcquire("financial");
            RefreshProgress progress = progressRegistry.start("financial", 13, RefreshTrigger.SCHEDULED, 31_000_000L, null);
            progress.stage(RefreshStage.STREAMING);
            progress.recordBatch(12_400_000, 1);

            DatasetStatus status = statusService.getStatus("financial");

            assertThat(status.activeVersion()).isEqualTo(12L); // queries are still served by V12
            assertThat(status.buildingVersion()).isEqualTo(13L);
            assertThat(status.refreshInProgress()).isTrue();
            assertThat(status.refreshStage()).isEqualTo(RefreshStage.STREAMING);
            assertThat(status.rowsProcessed()).isEqualTo(12_400_000L);
            assertThat(status.batchesProcessed()).isEqualTo(1L);
            assertThat(status.estimatedPercent()).isNotNull();
        }
    }

    @Test
    void statusOmitsLiveProgressFieldsWhenNoRefreshIsInFlight() {
        DataCacheProperties properties = properties();
        try (MetadataStore metadataStore = metadataStore(properties)) {
            metadataStore.createBuildingVersion("financial", 1, tempDir.resolve("financial_v1.duckdb"), "financial", "h1");
            metadataStore.activateVersion("financial", 1);

            RefreshProgressRegistry progressRegistry = new RefreshProgressRegistry(
                    new DataCacheMetrics(new SimpleMeterRegistry()));
            // A previous (now-finished) attempt is still sitting in the registry - the status API
            // must not present it as if it were currently in progress.
            progressRegistry.start("financial", 1, RefreshTrigger.MANUAL, null, null).recordBatch(1000, 1);

            DataCacheStatusServiceImpl statusService = new DataCacheStatusServiceImpl(properties, metadataStore,
                    new RefreshLock(), progressRegistry);

            DatasetStatus status = statusService.getStatus("financial");

            assertThat(status.refreshInProgress()).isFalse();
            assertThat(status.buildingVersion()).isNull();
            assertThat(status.refreshStage()).isNull();
            assertThat(status.rowsProcessed()).isNull();
        }
    }
}

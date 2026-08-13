package com.enterprise.datacache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.model.VersionState;
import com.enterprise.datacache.version.VersionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupRecoveryServiceTest {

    @TempDir
    Path tempDir;

    private DataCacheProperties properties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getDatasets().put("financial", new DatasetProperties());
        return properties;
    }

    private Path touch(String relative) throws Exception {
        Path p = tempDir.resolve("cache").resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "stub");
        return p;
    }

    @Test
    void interruptedBuildIsFailedAndActiveVersionSurvivesRestart() throws Exception {
        DataCacheProperties properties = properties();
        try (MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()),
                        properties.getDuckdb()))) {

            Path activeFile = touch("financial/financial_v2.duckdb");
            metadataStore.createBuildingVersion("financial", 2, activeFile, "financial", "hash-v2");
            metadataStore.activateVersion("financial", 2);

            // Simulate a crash mid-build: v3 never finished, no reader could possibly exist.
            Path buildingFile = touch("financial/financial_v3_building.duckdb");
            metadataStore.createBuildingVersion("financial", 3, buildingFile, "financial", "hash-v3");

            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            StartupRecoveryService recovery = new StartupRecoveryService(properties, metadataStore, versionManager);
            recovery.recoverAll();

            assertThat(metadataStore.findVersion("financial", 2).orElseThrow().state()).isEqualTo(VersionState.ACTIVE);
            assertThat(metadataStore.findVersion("financial", 3).orElseThrow().state()).isEqualTo(VersionState.FAILED);
            assertThat(buildingFile).doesNotExist();
            assertThat(activeFile).exists();
            assertThat(versionManager.findActive("financial").orElseThrow().version()).isEqualTo(2);
        }
    }

    @Test
    void missingActiveFileIsMarkedFailedSoDatasetReportsNoActiveVersion() throws Exception {
        DataCacheProperties properties = properties();
        try (MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()),
                        properties.getDuckdb()))) {

            Path missingFile = tempDir.resolve("cache/financial/financial_v1.duckdb"); // never actually created
            metadataStore.createBuildingVersion("financial", 1, missingFile, "financial", "hash-v1");
            metadataStore.activateVersion("financial", 1);

            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            new StartupRecoveryService(properties, metadataStore, versionManager).recoverAll();

            assertThat(metadataStore.findActive("financial")).isEmpty();
            assertThat(metadataStore.findVersion("financial", 1).orElseThrow().state()).isEqualTo(VersionState.FAILED);
        }
    }

    @Test
    void orphanFilesWithNoMetadataRowAreRemoved() throws Exception {
        DataCacheProperties properties = properties();
        try (MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()),
                        properties.getDuckdb()))) {

            Path orphan = touch("financial/financial_v99_building.duckdb");
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());
            new StartupRecoveryService(properties, metadataStore, versionManager).recoverAll();

            assertThat(orphan).doesNotExist();
        }
    }
}

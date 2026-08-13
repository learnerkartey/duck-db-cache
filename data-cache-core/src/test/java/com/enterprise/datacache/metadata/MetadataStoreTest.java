package com.enterprise.datacache.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.ValidationStatus;
import com.enterprise.datacache.model.VersionState;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataStoreTest {

    @TempDir
    Path tempDir;

    private DuckDbProperties props(Path dir) {
        DuckDbProperties p = new DuckDbProperties();
        p.setTempDirectory(dir.resolve("temp").toString());
        return p;
    }

    private Path metadataFile() {
        return MetadataStore.defaultMetadataFile(tempDir.toString());
    }

    @Test
    void createsSchemaAndPersistsVersionLifecycle() {
        try (MetadataStore store = new MetadataStore(DuckDbConnectionFactory.open(metadataFile(), props(tempDir)))) {
            long v1 = store.nextVersionNumber("financial");
            assertThat(v1).isEqualTo(1);

            store.createBuildingVersion("financial", v1, tempDir.resolve("financial_v1.duckdb"), "financial", "hash1");
            assertThat(store.findVersion("financial", v1)).isPresent();
            assertThat(store.findVersion("financial", v1).get().state()).isEqualTo(VersionState.BUILDING);

            store.recordLoadStats("financial", v1, 1000, 50_000);
            store.updateState("financial", v1, VersionState.VALIDATING);
            store.recordValidation("financial", v1, ValidationStatus.PASSED);
            store.recordCompletion("financial", v1, 5000);
            store.activateVersion("financial", v1);

            DatasetVersion active = store.findActive("financial").orElseThrow();
            assertThat(active.version()).isEqualTo(1);
            assertThat(active.state()).isEqualTo(VersionState.ACTIVE);
            assertThat(active.rowCount()).isEqualTo(1000);
            assertThat(active.validationStatus()).isEqualTo(ValidationStatus.PASSED);
            assertThat(active.durationMs()).isEqualTo(5000);

            // Second version activation demotes the first to PREVIOUS.
            long v2 = store.nextVersionNumber("financial");
            assertThat(v2).isEqualTo(2);
            store.createBuildingVersion("financial", v2, tempDir.resolve("financial_v2.duckdb"), "financial", "hash2");
            store.activateVersion("financial", v2);

            assertThat(store.findActive("financial").orElseThrow().version()).isEqualTo(2);
            List<DatasetVersion> previous = store.findPrevious("financial");
            assertThat(previous).hasSize(1);
            assertThat(previous.get(0).version()).isEqualTo(1);
        }
    }

    @Test
    void persistsAcrossCloseAndReopen() {
        Path file = metadataFile();
        try (MetadataStore store = new MetadataStore(DuckDbConnectionFactory.open(file, props(tempDir)))) {
            store.createBuildingVersion("organization", 1, tempDir.resolve("organization_v1.duckdb"), "organization", "h");
            store.activateVersion("organization", 1);
        }

        try (MetadataStore reopened = new MetadataStore(DuckDbConnectionFactory.open(file, props(tempDir)))) {
            Optional<DatasetVersion> active = reopened.findActive("organization");
            assertThat(active).isPresent();
            assertThat(active.get().version()).isEqualTo(1);
        }
    }

    @Test
    void recordsFailureWithoutActivating() {
        try (MetadataStore store = new MetadataStore(DuckDbConnectionFactory.open(metadataFile(), props(tempDir)))) {
            store.createBuildingVersion("headcount", 1, tempDir.resolve("headcount_v1.duckdb"), "headcount", "h");
            store.recordFailure("headcount", 1, "DREMIO_SOURCE_ERROR", "connection refused");

            DatasetVersion v = store.findVersion("headcount", 1).orElseThrow();
            assertThat(v.state()).isEqualTo(VersionState.FAILED);
            assertThat(v.errorCode()).isEqualTo("DREMIO_SOURCE_ERROR");
            assertThat(store.findActive("headcount")).isEmpty();
        }
    }
}

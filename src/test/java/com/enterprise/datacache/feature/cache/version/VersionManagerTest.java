package com.enterprise.datacache.feature.cache.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionManagerTest {

    @TempDir
    Path tempDir;

    private MetadataStore newMetadataStore() {
        DuckDbProperties props = new DuckDbProperties();
        props.setTempDirectory(tempDir.resolve("temp").toString());
        return new MetadataStore(DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(tempDir.toString()), props));
    }

    private Path touchFile(String name) throws Exception {
        Path p = tempDir.resolve(name);
        Files.createDirectories(p.getParent() == null ? tempDir : p.getParent());
        Files.writeString(p, "duckdb-file-stub");
        return p;
    }

    @Test
    void secondRefreshDemotesFirstToPreviousAndThirdCleansUpOldest() throws Exception {
        DuckDbProperties duckDbProps = new DuckDbProperties();
        duckDbProps.setMaxVersions(2);
        try (MetadataStore metadata = newMetadataStore()) {
            VersionManager manager = new VersionManager(metadata, duckDbProps);

            Path f1 = touchFile("financial_v1.duckdb");
            metadata.createBuildingVersion("financial", 1, f1, "financial", "h1");
            manager.activate("financial", 1);
            assertThat(manager.findActive("financial").orElseThrow().version()).isEqualTo(1);

            Path f2 = touchFile("financial_v2.duckdb");
            metadata.createBuildingVersion("financial", 2, f2, "financial", "h2");
            manager.activate("financial", 2);
            assertThat(manager.findActive("financial").orElseThrow().version()).isEqualTo(2);
            assertThat(metadata.findPrevious("financial")).hasSize(1);
            assertThat(f1).exists(); // still within retention (maxVersions=2: ACTIVE + 1 PREVIOUS)

            Path f3 = touchFile("financial_v3.duckdb");
            metadata.createBuildingVersion("financial", 3, f3, "financial", "h3");
            manager.activate("financial", 3);

            assertThat(manager.findActive("financial").orElseThrow().version()).isEqualTo(3);
            assertThat(metadata.findVersion("financial", 1)).isEmpty(); // cleaned up, beyond retention
            assertThat(f1).doesNotExist();
            assertThat(metadata.findPrevious("financial")).hasSize(1);
            assertThat(metadata.findPrevious("financial").get(0).version()).isEqualTo(2);
        }
    }

    @Test
    void runningQueryKeepsOldVersionPinnedWhileNewVersionActivatesConcurrently() throws Exception {
        DuckDbProperties duckDbProps = new DuckDbProperties();
        duckDbProps.setMaxVersions(2);
        try (MetadataStore metadata = newMetadataStore()) {
            VersionManager manager = new VersionManager(metadata, duckDbProps);

            Path f1 = touchFile("financial_v1.duckdb");
            metadata.createBuildingVersion("financial", 1, f1, "financial", "h1");
            manager.activate("financial", 1);

            VersionHandle handle = manager.pinActive("financial");
            assertThat(handle.version()).isEqualTo(1);

            CountDownLatch queryPaused = new CountDownLatch(1);
            CountDownLatch releaseQuery = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    queryPaused.countDown();
                    try {
                        releaseQuery.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(queryPaused.await(5, TimeUnit.SECONDS)).isTrue();

                // A new version becomes active while the "query" above still holds v1 pinned.
                Path f2 = touchFile("financial_v2.duckdb");
                metadata.createBuildingVersion("financial", 2, f2, "financial", "h2");
                manager.activate("financial", 2);

                // New pins now get v2 ...
                try (VersionHandle newHandle = manager.pinActive("financial")) {
                    assertThat(newHandle.version()).isEqualTo(2);
                }

                // ... but the in-flight query's handle still points at v1, and v1's file is not deleted
                // while it is still referenced. v1 is still within the retained window (ACTIVE=v2,
                // PREVIOUS=v1) so it would not be deleted yet regardless of readers.
                assertThat(handle.version()).isEqualTo(1);
                assertThat(f1).exists();
                assertThat(manager.activeReaderCount("financial", 1)).isEqualTo(1);

                // A third version now pushes v1 out of the retained window while it is still pinned.
                Path f3 = touchFile("financial_v3.duckdb");
                metadata.createBuildingVersion("financial", 3, f3, "financial", "h3");
                manager.activate("financial", 3);
                assertThat(f1).exists(); // deletion deferred: reader still holds v1

                releaseQuery.countDown();
            } finally {
                executor.shutdown();
            }

            // Releasing the handle triggers deferred cleanup since v1 is now beyond the retained window.
            handle.close();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(f1).doesNotExist());
            assertThat(metadata.findVersion("financial", 1)).isEmpty();
        }
    }

    @Test
    void safeDeletionWaitsForAllReadersToRelease() throws Exception {
        DuckDbProperties duckDbProps = new DuckDbProperties();
        duckDbProps.setMaxVersions(2);
        try (MetadataStore metadata = newMetadataStore()) {
            VersionManager manager = new VersionManager(metadata, duckDbProps);

            Path f1 = touchFile("organization_v1.duckdb");
            metadata.createBuildingVersion("organization", 1, f1, "organization", "h1");
            manager.activate("organization", 1);

            VersionHandle readerA = manager.pinActive("organization");
            VersionHandle readerB = manager.pinActive("organization");
            assertThat(manager.activeReaderCount("organization", 1)).isEqualTo(2);

            Path f2 = touchFile("organization_v2.duckdb");
            metadata.createBuildingVersion("organization", 2, f2, "organization", "h2");
            manager.activate("organization", 2);

            Path f3 = touchFile("organization_v3.duckdb");
            metadata.createBuildingVersion("organization", 3, f3, "organization", "h3");
            manager.activate("organization", 3); // v1 now beyond retention, but still has 2 readers

            assertThat(f1).exists();
            readerA.close();
            assertThat(f1).exists(); // one reader remains
            readerB.close();
            assertThat(f1).doesNotExist(); // last reader released -> deleted
        }
    }
}

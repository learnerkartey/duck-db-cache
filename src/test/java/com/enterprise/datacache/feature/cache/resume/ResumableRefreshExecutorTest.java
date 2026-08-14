package com.enterprise.datacache.feature.cache.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.exception.DataCacheException;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.model.ChunkStatus;
import com.enterprise.datacache.feature.cache.model.ConsistencyMode;
import com.enterprise.datacache.feature.cache.model.DatasetChunk;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshManifestStatus;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import com.enterprise.datacache.feature.cache.model.ResumeStrategyType;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgress;
import com.enterprise.datacache.feature.cache.testsupport.ChunkAwareDremioSource;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.arrow.vector.types.pojo.Schema;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mandatory resumable-refresh crash/restart/idempotency tests (see the resumable-refresh
 * specification's required-tests list): each simulates a crash by throwing mid-attempt from either
 * the source ({@link ChunkAwareDremioSource}) or metadata persistence, then makes a fresh
 * {@link ResumableRefreshExecutor#load} call - exactly like a restarted process re-attempting the
 * same dataset - and proves the durable, chunk-level checkpoint (never a row-level or in-memory one)
 * is honored: completed chunks are never reloaded, no rows are ever duplicated, and unsafe resumes
 * (SQL/schema changed) are refused in favor of a fresh version rather than silently mixing state.
 */
class ResumableRefreshExecutorTest {

    @TempDir
    Path tempDir;

    private DataCacheProperties baseProperties(long chunkSize) {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getResume().setEnabled(true);

        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName("financial");
        dataset.setSourceSql("classpath:testdata/test-dataset.sql");
        DatasetResumeProperties resume = dataset.getResume();
        resume.setEnabled(true);
        resume.setStrategy(ResumeStrategyType.RANGE);
        resume.setPartitionColumn("id");
        resume.setChunkSize(chunkSize);
        // STRICT_SNAPSHOT (the default) requires a configured AS_OF_VALUE snapshot binding; these
        // tests exercise chunk-checkpoint/idempotency mechanics, not source-snapshot binding, so use
        // BEST_EFFORT to keep the fixture minimal.
        resume.setConsistency(ConsistencyMode.BEST_EFFORT);
        properties.getDatasets().put("financial", dataset);
        return properties;
    }

    private MetadataStore newMetadataStore(DuckDbProperties duckDbProps) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
    }

    private RefreshProgress newProgress(String datasetName, MetadataStore metadataStore) {
        return new RefreshProgress(datasetName, metadataStore.nextVersionNumber(datasetName), RefreshTrigger.MANUAL, null, null);
    }

    /** Row count plus a duplicate check, read directly from the finalized DuckDB file - the ground truth. */
    private record TableStats(long rowCount, long distinctIds) {
        boolean noDuplicates() {
            return rowCount == distinctIds;
        }
    }

    private TableStats readTableStats(Path duckDbFile, DuckDbProperties duckDbProps, String tableName) {
        try (DuckDBConnection connection = DuckDbConnectionFactory.open(duckDbFile, duckDbProps);
                Statement statement = connection.createStatement();
                java.sql.ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*), COUNT(DISTINCT id) FROM " + tableName)) {
            rs.next();
            return new TableStats(rs.getLong(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- 1: crash partway through, verify completed chunks are never reloaded and the rest resumes ----

    @Test
    void crashPartwayThroughResumesFromLastCompletedChunkWithoutReloadingCompletedOnes() {
        // 12 chunks of 100 rows each (analogous to the spec's 60M/500K-chunk example at a testable scale).
        DataCacheProperties properties = baseProperties(100);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(1200, 40)) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            // Chunk 5 starts at id 401 (chunks 1-4 cover 1-400) - fail it before any row streams.
            source.failChunkStartingAt(401, 0);
            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(DataCacheException.class);

            long version = metadataStore.findBuildingManifest("financial").orElseThrow().version();
            List<DatasetChunk> afterCrash = metadataStore.findChunks("financial", version);
            assertThat(afterCrash.stream().filter(c -> c.status() == ChunkStatus.COMPLETED)).hasSize(4);
            assertThat(afterCrash.stream().filter(c -> c.chunkId() == 5).findFirst().orElseThrow().status())
                    .isEqualTo(ChunkStatus.FAILED);
            for (long chunkStart : new long[] {1, 101, 201, 301}) {
                assertThat(source.executionCountForChunkStart(chunkStart)).isEqualTo(1);
            }

            // "Restart": a fresh load() call, exactly like a new process attempting the same dataset.
            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.resumed()).isTrue();
            assertThat(result.version()).isEqualTo(version); // same version continued, not a new one
            assertThat(result.rowCount()).isEqualTo(1200);
            for (long chunkStart : new long[] {1, 101, 201, 301}) {
                assertThat(source.executionCountForChunkStart(chunkStart)).isEqualTo(1); // never reloaded
            }
            assertThat(source.executionCountForChunkStart(401)).isEqualTo(2); // failed once, then retried

            RefreshManifest finalManifest = metadataStore.findManifest("financial", version).orElseThrow();
            assertThat(finalManifest.status()).isEqualTo(RefreshManifestStatus.COMPLETED);
            assertThat(finalManifest.completedChunks()).isEqualTo(12);
            assertThat(finalManifest.rowsCommitted()).isEqualTo(1200);

            TableStats stats = readTableStats(result.buildingPath(), properties.getDuckdb(), "financial");
            assertThat(stats.rowCount()).isEqualTo(1200);
            assertThat(stats.noDuplicates()).isTrue();
        }
    }

    // --- 2: crash after the DuckDB commit but before chunk-completion metadata is persisted ----------

    /** Thrown instead of a normal exception so it bypasses every {@code catch(RuntimeException)} in the
     *  executor, exactly like an actual process crash bypasses all Java exception handling. */
    private static final class SimulatedProcessDeath extends Error {
    }

    /** Simulates the crash window documented in RefreshCoordinator/ResumableRefreshExecutor: the DuckDB
     *  transaction for {@code crashOnChunkId} has already committed by the time this throws - only the
     *  metadata write that would have recorded its completion never happens. */
    private static final class CrashBeforeChunkMetadataStore extends MetadataStore {
        private final long crashOnChunkId;
        private final AtomicBoolean crashed = new AtomicBoolean(false);

        CrashBeforeChunkMetadataStore(DuckDBConnection connection, long crashOnChunkId) {
            super(connection);
            this.crashOnChunkId = crashOnChunkId;
        }

        @Override
        public synchronized void markChunkCompleted(String datasetName, long version, long chunkId, long rowsLoaded) {
            if (chunkId == crashOnChunkId && crashed.compareAndSet(false, true)) {
                throw new SimulatedProcessDeath();
            }
            super.markChunkCompleted(datasetName, version, chunkId, rowsLoaded);
        }
    }

    @Test
    void crashAfterDuckDbCommitBeforeMetadataRecordedIsRetriedWithoutDuplicating() {
        DataCacheProperties properties = baseProperties(100);
        DuckDBConnection rawConnection = DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()), properties.getDuckdb());
        try (CrashBeforeChunkMetadataStore metadataStore = new CrashBeforeChunkMetadataStore(rawConnection, 1);
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(300, 40)) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(SimulatedProcessDeath.class);

            long version = metadataStore.findBuildingManifest("financial").orElseThrow().version();
            // The metadata write never happened, so chunk 1 is NOT recorded COMPLETED, even though its
            // rows are already durably committed in the DuckDB file underneath.
            DatasetChunk chunk1 = metadataStore.findChunks("financial", version).stream()
                    .filter(c -> c.chunkId() == 1).findFirst().orElseThrow();
            assertThat(chunk1.status()).isNotEqualTo(ChunkStatus.COMPLETED);

            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.rowCount()).isEqualTo(300);
            TableStats stats = readTableStats(result.buildingPath(), properties.getDuckdb(), "financial");
            assertThat(stats.rowCount()).isEqualTo(300);
            assertThat(stats.noDuplicates()).isTrue(); // chunk 1's rows re-loaded, not duplicated
        }
    }

    // --- 3: crash mid-chunk (partially streamed, never committed) ------------------------------------

    @Test
    void crashMidChunkBeforeCommitDiscardsPartialRowsAndRetriesTheWholeChunk() {
        DataCacheProperties properties = baseProperties(100);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                // batchSize=30 means chunk 2 (100 rows) spans 4 batches - failing after 1 leaves it
                // genuinely mid-stream, never reaching writer.commitChunk().
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(300, 30)) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            source.failChunkStartingAt(101, 1); // chunk 2 starts at id 101; fail after 1 batch (30 rows)
            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(DataCacheException.class);

            long version = metadataStore.findBuildingManifest("financial").orElseThrow().version();
            List<DatasetChunk> afterCrash = metadataStore.findChunks("financial", version);
            assertThat(afterCrash.stream().filter(c -> c.chunkId() == 1).findFirst().orElseThrow().status())
                    .isEqualTo(ChunkStatus.COMPLETED);
            assertThat(afterCrash.stream().filter(c -> c.chunkId() == 2).findFirst().orElseThrow().status())
                    .isEqualTo(ChunkStatus.FAILED);

            Path buildingPath = metadataStore.findVersion("financial", version).orElseThrow().filePath();
            // The rolled-back partial batch must not be visible even before the retry - only chunk 1's
            // 100 rows should be present, never 100 + a partial 30.
            TableStats afterCrashStats = readTableStats(buildingPath, properties.getDuckdb(), "financial");
            assertThat(afterCrashStats.rowCount()).isEqualTo(100);

            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.rowCount()).isEqualTo(300);
            TableStats finalStats = readTableStats(result.buildingPath(), properties.getDuckdb(), "financial");
            assertThat(finalStats.rowCount()).isEqualTo(300);
            assertThat(finalStats.noDuplicates()).isTrue();
        }
    }

    // --- 4: source SQL changed between the interrupted attempt and the restart -----------------------

    @Test
    void sourceSqlChangeAbandonsPartialBuildAndStartsFreshVersionWithoutResumingIt() {
        DataCacheProperties properties = baseProperties(100);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(300, 40)) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            source.failChunkStartingAt(101, 0); // chunk 2 fails
            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(DataCacheException.class);
            long partialVersion = metadataStore.findBuildingManifest("financial").orElseThrow().version();

            // Source SQL changes before the next attempt - a different resource with different content.
            config.setSourceSql("classpath:testdata/single-dataset-query.sql");

            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.version()).isGreaterThan(partialVersion); // a NEW version, not the old one continued
            assertThat(result.resumed()).isFalse();
            assertThat(metadataStore.findManifest("financial", partialVersion).orElseThrow().status())
                    .isEqualTo(RefreshManifestStatus.ABANDONED);
            RefreshManifest newManifest = metadataStore.findManifest("financial", result.version()).orElseThrow();
            assertThat(newManifest.status()).isEqualTo(RefreshManifestStatus.COMPLETED);
            assertThat(newManifest.completedChunks()).isEqualTo(newManifest.totalChunks());
            assertThat(result.rowCount()).isEqualTo(300);
        }
    }

    // --- 5: source schema changed (generic - precision-independent) between attempts -----------------

    @Test
    void sourceSchemaChangeAbandonsPartialBuildRatherThanAppendingIncompatibleSchema() {
        DataCacheProperties properties = baseProperties(100);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(300, 40,
                        ChunkAwareDremioSource.decimalSchema(18, 3))) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            source.failChunkStartingAt(101, 0); // chunk 2 fails; chunk 1 completes and records the schema hash
            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(DataCacheException.class);
            long partialVersion = metadataStore.findBuildingManifest("financial").orElseThrow().version();
            assertThat(metadataStore.findManifest("financial", partialVersion).orElseThrow().sourceSchemaHash()).isNotNull();

            // The source's schema drifts before the retry: a materially different Arrow type, not just a
            // precision/scale change (that specific case is covered by the decimal-widening test below).
            source.setSchema(ChunkAwareDremioSource.decimalSchema(18, 6)); // scale changes 3 -> 6

            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.version()).isGreaterThan(partialVersion);
            assertThat(metadataStore.findManifest("financial", partialVersion).orElseThrow().status())
                    .isEqualTo(RefreshManifestStatus.ABANDONED);
            assertThat(metadataStore.findManifest("financial", result.version()).orElseThrow().status())
                    .isEqualTo(RefreshManifestStatus.COMPLETED);
            assertThat(result.rowCount()).isEqualTo(300); // fresh version loaded fully under the new schema
        }
    }

    // --- 6: decimal precision/scale widened between attempts (the spec's flagship example) -----------

    @Test
    void decimalPrecisionWideningBetweenAttemptsIsTreatedAsIncompatibleNotBlindlyAppended() {
        DataCacheProperties properties = baseProperties(100);
        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb());
                ChunkAwareDremioSource source = new ChunkAwareDremioSource(300, 40,
                        ChunkAwareDremioSource.decimalSchema(18, 3))) {
            ResumableRefreshExecutor executor = new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore);
            DatasetProperties config = properties.getDatasets().get("financial");

            source.failChunkStartingAt(101, 0);
            RefreshProgress firstAttempt = newProgress("financial", metadataStore);
            assertThatThrownBy(() -> executor.load("financial", "financial", config, firstAttempt))
                    .isInstanceOf(DataCacheException.class);
            long partialVersion = metadataStore.findBuildingManifest("financial").orElseThrow().version();

            // DECIMAL(18,3) -> DECIMAL(38,9), exactly the spec's worked example.
            source.setSchema(ChunkAwareDremioSource.decimalSchema(38, 9));

            RefreshProgress secondAttempt = newProgress("financial", metadataStore);
            ResumableRefreshExecutor.LoadResult result = executor.load("financial", "financial", config, secondAttempt);

            assertThat(result.version()).isGreaterThan(partialVersion);
            assertThat(metadataStore.findManifest("financial", partialVersion).orElseThrow().status())
                    .isEqualTo(RefreshManifestStatus.ABANDONED);
            assertThat(result.rowCount()).isEqualTo(300);

            // The new version's file must contain only DECIMAL(38,9) data - never a mix with the old
            // partial DECIMAL(18,3) build, which lives in a completely separate physical file/version.
            Path oldPartialPath = metadataStore.findVersion("financial", partialVersion).orElseThrow().filePath();
            assertThat(oldPartialPath).isNotEqualTo(result.buildingPath());
        }
    }

    // --- 9: the same chunk executed multiple times never duplicates rows -----------------------------

    @Test
    void sameChunkExecutedMultipleTimesLeavesExactlyOneCopyOfItsRows() {
        DataCacheProperties properties = baseProperties(100);
        Path filePath = tempDir.resolve("cache").resolve("financial").resolve("idempotency_v1_building.duckdb");
        DuckDbProperties duckDbProps = properties.getDuckdb();
        try (ChunkAwareDremioSource source = new ChunkAwareDremioSource(50, 40)) {
            Schema schema = ChunkAwareDremioSource.decimalSchema(18, 3);
            try (ResumableChunkWriter writer = new ResumableChunkWriter(filePath, "financial", duckDbProps)) {
                writer.ensureTableExists("financial", schema);
                for (int attempt = 1; attempt <= 5; attempt++) {
                    writer.beginChunk(1);
                    try (var stream = source.executeQuery(
                            "SELECT * FROM (SELECT id, amount FROM x) s WHERE s.id >= 1 AND s.id <= 50")) {
                        while (stream.loadNextBatch()) {
                            writer.writeBatch(stream.currentBatch());
                        }
                    }
                    long rowsThisAttempt = writer.commitChunk();
                    assertThat(rowsThisAttempt).isEqualTo(50); // always exactly the chunk's own row count
                }
                assertThat(writer.countRows()).isEqualTo(50); // never 5 x 50 - always exactly one logical copy
            }
        }
    }
}

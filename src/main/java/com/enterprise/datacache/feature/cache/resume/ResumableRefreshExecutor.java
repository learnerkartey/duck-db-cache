package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.config.GlobalResumeProperties;
import com.enterprise.datacache.feature.cache.config.ProgressLoggingProperties;
import com.enterprise.datacache.feature.cache.exception.DataCacheException;
import com.enterprise.datacache.feature.cache.exception.ResumeIncompatibleException;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.model.ChunkStatus;
import com.enterprise.datacache.feature.cache.model.DatasetChunk;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshManifestStatus;
import com.enterprise.datacache.feature.cache.model.SnapshotMode;
import com.enterprise.datacache.feature.cache.refresh.RefreshCoordinator;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgress;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgressLogger;
import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import com.enterprise.datacache.feature.cache.util.Sha256;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads one dataset's BUILDING version as a sequence of durable, independently resumable chunks
 * instead of one continuous Arrow stream. Never assumes a broken Flight stream can resume
 * mid-stream: a chunk that fails is always re-run from its own start. The durable checkpoint a
 * restart relies on is a chunk reaching {@link ChunkStatus#COMPLETED} in persisted metadata - never
 * an in-memory row count. See docs/RESUMABLE-REFRESH-AND-RECOVERY.md.
 */
public class ResumableRefreshExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResumableRefreshExecutor.class);

    private final DataCacheProperties properties;
    private final DremioSource dremioSource;
    private final SqlResourceLoader sqlResourceLoader;
    private final MetadataStore metadataStore;

    public ResumableRefreshExecutor(DataCacheProperties properties, DremioSource dremioSource,
            SqlResourceLoader sqlResourceLoader, MetadataStore metadataStore) {
        this.properties = properties;
        this.dremioSource = dremioSource;
        this.sqlResourceLoader = sqlResourceLoader;
        this.metadataStore = metadataStore;
    }

    public record LoadResult(long version, Path buildingPath, long rowCount, List<String> columnNames,
            boolean resumed, long dremioSetupNanos, long firstBatchNanos, long arrowTransferNanos,
            long duckDbWriteNanos) {
    }

    public LoadResult load(String datasetName, String tableName, DatasetProperties config, RefreshProgress progress) {
        DatasetResumeProperties resumeConfig = config.getResume();
        String rawSourceSql = sqlResourceLoader.load(config.getSourceSql());
        String sqlHash = Sha256.hex(rawSourceSql);

        Decision decision = resolveManifest(datasetName, tableName, config, resumeConfig, sqlHash);
        progress.updateVersion(decision.manifest.version());
        try {
            return executeChunks(datasetName, tableName, resumeConfig, rawSourceSql, decision, progress);
        } catch (ResumeIncompatibleException e) {
            log.warn("event=dataset-resume-abandoned dataset={} version={} reason={}",
                    datasetName, decision.manifest.version(), e.getMessage());
            metadataStore.updateManifestStatus(datasetName, decision.manifest.version(), RefreshManifestStatus.ABANDONED);
            // Exactly one fallback attempt within this call - never recurses further. A fresh
            // manifest/version/chunk plan is created from scratch; the abandoned BUILDING version's
            // file is left for the caller (RefreshCoordinator's failure path / next StartupRecoveryService
            // pass) to clean up - the ACTIVE version is never touched by any of this.
            Decision fresh = createFreshManifest(datasetName, tableName, resumeConfig, sqlHash);
            progress.updateVersion(fresh.manifest.version());
            return executeChunks(datasetName, tableName, resumeConfig, rawSourceSql, fresh, progress);
        }
    }

    private record Decision(RefreshManifest manifest, boolean resumed) {
    }

    private Decision resolveManifest(String datasetName, String tableName, DatasetProperties config,
            DatasetResumeProperties resumeConfig, String sqlHash) {
        Optional<RefreshManifest> existing = metadataStore.findBuildingManifest(datasetName);
        if (existing.isPresent() && isCheaplyCompatible(existing.get(), resumeConfig, sqlHash)) {
            RefreshManifest manifest = existing.get();
            log.info("event=dataset-refresh-resuming dataset={} version={} completedChunks={} totalChunks={} "
                    + "rowsCommitted={} nextChunk={}",
                    datasetName, manifest.version(), manifest.completedChunks(), manifest.totalChunks(),
                    manifest.rowsCommitted(), manifest.completedChunks() + 1);
            return new Decision(manifest, true);
        }
        if (existing.isPresent()) {
            String reason = incompatibilityReason(existing.get(), resumeConfig, sqlHash);
            log.warn("event=dataset-resume-abandoned dataset={} version={} reason={}",
                    datasetName, existing.get().version(), reason);
            metadataStore.updateManifestStatus(datasetName, existing.get().version(), RefreshManifestStatus.ABANDONED);
        }
        return createFreshManifest(datasetName, tableName, resumeConfig, sqlHash);
    }

    private Decision createFreshManifest(String datasetName, String tableName, DatasetResumeProperties resumeConfig,
            String sqlHash) {
        long version = metadataStore.nextVersionNumber(datasetName);
        Path buildingPath = RefreshCoordinator.datasetDirectory(properties.getDuckdb(), datasetName)
                .resolve(tableName + "_v" + version + "_building.duckdb");
        metadataStore.createBuildingVersion(datasetName, version, buildingPath, tableName, sqlHash);

        Instant now = Instant.now();
        Instant snapshotInstant = resumeConfig.getSnapshot().getMode() == SnapshotMode.AS_OF_VALUE ? now : null;
        String refreshId = UUID.randomUUID().toString();

        RefreshManifest manifest = new RefreshManifest(datasetName, version, refreshId, sqlHash, null, true,
                resumeConfig.getStrategy(), resumeConfig.getPartitionColumn(), resumeConfig.getChunkSize(),
                snapshotInstant == null ? null : snapshotInstant.toString(), resumeConfig.getConsistency(),
                null, 0, 0, 0, RefreshManifestStatus.BUILDING, now, now);
        metadataStore.createManifest(manifest);

        // Chunk planning happens lazily in executeChunks (triggered by an empty chunk list), using
        // the same possibly-snapshot-bound SQL text that will also be used to load each chunk.
        return new Decision(manifest, false);
    }

    private boolean isCheaplyCompatible(RefreshManifest manifest, DatasetResumeProperties resumeConfig, String sqlHash) {
        if (!manifest.resumeEnabled() || !resumeConfig.isEnabled()) {
            return false;
        }
        if (!properties.getResume().isEnabled()) {
            return false;
        }
        if (!Objects.equals(manifest.sourceSqlHash(), sqlHash)) {
            return false;
        }
        if (manifest.resumeStrategy() != resumeConfig.getStrategy()) {
            return false;
        }
        if (!Objects.equals(manifest.partitionColumn(), resumeConfig.getPartitionColumn())) {
            return false;
        }
        if (!Objects.equals(manifest.chunkSize(), resumeConfig.getChunkSize())) {
            return false;
        }
        GlobalResumeProperties globalResume = properties.getResume();
        java.time.Duration age = java.time.Duration.between(manifest.startedAt(), Instant.now());
        return age.compareTo(globalResume.getMaxResumeAge()) <= 0;
    }

    private String incompatibilityReason(RefreshManifest manifest, DatasetResumeProperties resumeConfig, String sqlHash) {
        if (!Objects.equals(manifest.sourceSqlHash(), sqlHash)) {
            return "source SQL changed (was " + manifest.sourceSqlHash() + ", now " + sqlHash + ")";
        }
        if (manifest.resumeStrategy() != resumeConfig.getStrategy()) {
            return "resume strategy changed (was " + manifest.resumeStrategy() + ", now " + resumeConfig.getStrategy() + ")";
        }
        if (!Objects.equals(manifest.partitionColumn(), resumeConfig.getPartitionColumn())) {
            return "partition-column changed (was " + manifest.partitionColumn() + ", now " + resumeConfig.getPartitionColumn() + ")";
        }
        if (!Objects.equals(manifest.chunkSize(), resumeConfig.getChunkSize())) {
            return "chunk-size changed (was " + manifest.chunkSize() + ", now " + resumeConfig.getChunkSize() + ")";
        }
        return "BUILDING version older than data-cache.resume.max-resume-age ("
                + properties.getResume().getMaxResumeAge() + ")";
    }

    private LoadResult executeChunks(String datasetName, String tableName, DatasetResumeProperties resumeConfig,
            String rawSourceSql, Decision decision, RefreshProgress progress) {
        RefreshManifest manifest = decision.manifest;
        long version = manifest.version();
        Path buildingPath = metadataStore.findVersion(datasetName, version)
                .orElseThrow(() -> new DataCacheException("RESUME_STATE_INCONSISTENT",
                        "No dataset_versions row for resumable manifest " + datasetName + " v" + version, false))
                .filePath();
        DuckDbProperties duckDbProperties = properties.getDuckdb();

        Instant snapshotInstant = manifest.sourceSnapshotId() == null ? null : Instant.parse(manifest.sourceSnapshotId());
        String boundSql = (snapshotInstant != null)
                ? SnapshotParameterBinder.bind(rawSourceSql, resumeConfig.getSnapshot().getParameterName(), snapshotInstant)
                : rawSourceSql;

        ResumePartitionStrategy strategy = ResumePartitionStrategyFactory.forType(resumeConfig.getStrategy());

        int reset = metadataStore.resetStaleRunningChunksToPending(datasetName, version);
        if (reset > 0) {
            log.warn("event=dataset-resume-stale-running-chunks-reset dataset={} version={} count={}",
                    datasetName, version, reset);
        }

        List<DatasetChunk> chunks = metadataStore.findChunks(datasetName, version);
        if (chunks.isEmpty()) {
            List<ChunkDefinition> planned = strategy.planChunks(dremioSource, boundSql, resumeConfig);
            metadataStore.planChunks(datasetName, version, planned);
            metadataStore.updateManifestTotalChunks(datasetName, version, planned.size());
            chunks = metadataStore.findChunks(datasetName, version);
            log.info("event=dataset-resume-chunks-planned dataset={} version={} totalChunks={}",
                    datasetName, version, planned.size());
        }

        long completedChunks = chunks.stream().filter(c -> c.status() == ChunkStatus.COMPLETED).count();
        long rowsAlreadyCommitted = chunks.stream().filter(c -> c.status() == ChunkStatus.COMPLETED)
                .mapToLong(DatasetChunk::rowsLoaded).sum();
        if (decision.resumed) {
            progress.markResuming(completedChunks, (long) chunks.size(), rowsAlreadyCommitted);
        } else {
            progress.updateChunkProgress(completedChunks, (long) chunks.size(), null);
        }

        ProgressLoggingProperties progressLogging = properties.getLogging().getProgress();

        // Mutable across the whole chunk loop (not just the manifest's original, immutable value)
        // so that once chunk 1 of a fresh build records a schema hash, chunk 2..N of that SAME
        // build - and every future resume - all compare against it, not just resumed attempts.
        String[] recordedSchemaHash = {manifest.sourceSchemaHash()};
        long dremioSetupNanos = 0;
        long firstBatchNanos = 0;
        long arrowTransferNanos = 0;
        long duckDbWriteNanos = 0;
        boolean firstChunkProcessed = false;

        try (ResumableChunkWriter writer = new ResumableChunkWriter(buildingPath, tableName, duckDbProperties)) {
            boolean tableEnsured = false;
            for (DatasetChunk chunk : chunks) {
                if (chunk.status() == ChunkStatus.COMPLETED) {
                    continue; // durable checkpoint reached previously - never reloaded
                }
                progress.updateChunkProgress(completedChunks, (long) chunks.size(), chunk.chunkId());
                metadataStore.markChunkRunning(datasetName, version, chunk.chunkId());
                String chunkSql = strategy.buildChunkSql(boundSql, chunk.toDefinition(), resumeConfig);

                long setupStart = System.nanoTime();
                try (ArrowBatchStream stream = dremioSource.executeQuery(chunkSql)) {
                    dremioSetupNanos += System.nanoTime() - setupStart;

                    if (recordedSchemaHash[0] == null) {
                        String schemaHash = hashSchema(stream.schema());
                        metadataStore.updateManifestSchemaHash(datasetName, version, schemaHash);
                        recordedSchemaHash[0] = schemaHash;
                    } else {
                        String schemaHash = hashSchema(stream.schema());
                        if (!schemaHash.equals(recordedSchemaHash[0])) {
                            throw new ResumeIncompatibleException("source schema changed since this BUILDING version "
                                    + "started (was " + recordedSchemaHash[0] + ", now " + schemaHash
                                    + ") - refusing to append an incompatible schema into a partially-built cache");
                        }
                    }

                    if (!tableEnsured) {
                        writer.ensureTableExists(datasetName, stream.schema());
                        tableEnsured = true;
                    }

                    writer.beginChunk(chunk.chunkId());
                    boolean firstBatchOfChunk = true;
                    long firstBatchWaitStart = System.nanoTime();
                    while (true) {
                        long beforeNext = System.nanoTime();
                        boolean hasBatch = stream.loadNextBatch();
                        long afterNext = System.nanoTime();
                        if (!firstChunkProcessed && firstBatchOfChunk && hasBatch) {
                            firstBatchNanos = afterNext - firstBatchWaitStart;
                        }
                        firstBatchOfChunk = false;
                        if (!hasBatch) {
                            break;
                        }
                        arrowTransferNanos += afterNext - beforeNext;

                        VectorSchemaRoot batch = stream.currentBatch();
                        int rowsInBatch = batch.getRowCount();
                        long beforeWrite = System.nanoTime();
                        writer.writeBatch(batch);
                        duckDbWriteNanos += System.nanoTime() - beforeWrite;
                        progress.recordRead(rowsInBatch); // read, not yet durably committed
                        RefreshProgressLogger.logIfDue(progress, progressLogging, () -> fileSizeQuietly(buildingPath));
                    }
                    firstChunkProcessed = true;

                    long commitStart = System.nanoTime();
                    long rowsCommittedThisChunk = writer.commitChunk(); // <<< durable commit boundary
                    duckDbWriteNanos += System.nanoTime() - commitStart;

                    // Two-phase ordering (see docs/RESUMABLE-REFRESH-AND-RECOVERY.md#metadata-update-order):
                    // the DuckDB transaction above is already durably committed; if the process
                    // crashes between here and the metadata write below, the next attempt sees this
                    // chunk still PENDING/RUNNING and safely re-runs it - the idempotent
                    // DELETE-then-reload in beginChunk() means that is never a duplicate.
                    metadataStore.markChunkCompleted(datasetName, version, chunk.chunkId(), rowsCommittedThisChunk);
                    completedChunks++;
                    long totalRowsCommitted = rowsAlreadyCommitted(datasetName, version);
                    metadataStore.recordManifestChunkProgress(datasetName, version, completedChunks,
                            countFailedChunks(datasetName, version), totalRowsCommitted);
                    progress.clearFailedChunk();
                    progress.updateChunkProgress(completedChunks, (long) chunks.size(), null);
                    progress.recordBatch(rowsCommittedThisChunk, 0L);
                } catch (ResumeIncompatibleException e) {
                    writer.rollbackChunk();
                    throw e;
                } catch (DataCacheException e) {
                    writer.rollbackChunk();
                    metadataStore.markChunkFailed(datasetName, version, chunk.chunkId(), e.getErrorCode(), e.getMessage());
                    progress.recordChunkFailed(chunk.chunkId());
                    metadataStore.recordManifestChunkProgress(datasetName, version, completedChunks,
                            countFailedChunks(datasetName, version), rowsAlreadyCommitted(datasetName, version));
                    throw e;
                } catch (RuntimeException e) {
                    writer.rollbackChunk();
                    metadataStore.markChunkFailed(datasetName, version, chunk.chunkId(), "UNEXPECTED_ERROR", String.valueOf(e.getMessage()));
                    progress.recordChunkFailed(chunk.chunkId());
                    metadataStore.recordManifestChunkProgress(datasetName, version, completedChunks,
                            countFailedChunks(datasetName, version), rowsAlreadyCommitted(datasetName, version));
                    throw new DataCacheException("UNEXPECTED_ERROR", "Unexpected chunk failure: " + e.getMessage(), true, e);
                }
            }

            writer.finalizeTable();
            long totalRows = writer.countRows();
            metadataStore.updateManifestStatus(datasetName, version, RefreshManifestStatus.COMPLETED);
            return new LoadResult(version, buildingPath, totalRows, writer.columnNames(), decision.resumed,
                    dremioSetupNanos, firstBatchNanos, arrowTransferNanos, duckDbWriteNanos);
        }
    }

    private long rowsAlreadyCommitted(String datasetName, long version) {
        return metadataStore.findChunks(datasetName, version).stream()
                .filter(c -> c.status() == ChunkStatus.COMPLETED)
                .mapToLong(DatasetChunk::rowsLoaded).sum();
    }

    private long countFailedChunks(String datasetName, long version) {
        return metadataStore.findChunks(datasetName, version).stream()
                .filter(c -> c.status() == ChunkStatus.FAILED).count();
    }

    private static long fileSizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    private static String hashSchema(Schema schema) {
        StringBuilder sb = new StringBuilder();
        for (Field field : schema.getFields()) {
            sb.append(field.getName()).append(':').append(field.getType()).append(';');
        }
        return Sha256.hex(sb.toString());
    }
}

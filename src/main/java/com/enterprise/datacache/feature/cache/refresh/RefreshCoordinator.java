package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.config.ProgressLoggingProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbDatasetWriter;
import com.enterprise.datacache.feature.cache.exception.DataCacheException;
import com.enterprise.datacache.feature.cache.exception.DatasetNotFoundException;
import com.enterprise.datacache.feature.cache.exception.ValidationFailedException;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.DatasetVersion;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshManifestStatus;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import com.enterprise.datacache.feature.cache.model.RefreshStage;
import com.enterprise.datacache.feature.cache.model.RefreshTimings;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import com.enterprise.datacache.feature.cache.model.ValidationStatus;
import com.enterprise.datacache.feature.cache.model.VersionState;
import com.enterprise.datacache.feature.cache.resume.ResumableRefreshExecutor;
import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import com.enterprise.datacache.feature.cache.util.Sha256;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import com.enterprise.datacache.feature.cache.validation.DatasetValidationService;
import com.enterprise.datacache.feature.cache.validation.ValidationOutcome;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Orchestrates the full lifecycle of one dataset refresh: acquire the per-dataset lock, load the
 * BUILDING DuckDB file - either as one continuous Arrow stream, or (when
 * {@code data-cache.datasets.<name>.resume.enabled=true}) as a sequence of durable, independently
 * resumable chunks via {@link ResumableRefreshExecutor} - then validate and atomically activate,
 * all wrapped in the dataset's configured retry policy. Concurrency across different datasets is
 * bounded by the caller (see {@code DataCacheRefreshServiceImpl}), not by this class.
 *
 * <p>Every attempt is tracked end to end through a single {@link RefreshProgress} instance
 * (registered with {@link RefreshProgressRegistry} for the status API and metrics to read live),
 * and reports a structured sequence of INFO-level log events - started, Dremio query started,
 * first batch (and, for resumable datasets, resuming), periodic progress, load completed,
 * validation, activation, and a final completed/failed summary - regardless of whether the refresh
 * was triggered by startup, a cron schedule, a manual API call, or {@code refreshAll()}. Individual
 * Arrow batches are never logged at INFO.
 */
public class RefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RefreshCoordinator.class);

    private final DataCacheProperties properties;
    private final DremioSource dremioSource;
    private final SqlResourceLoader sqlResourceLoader;
    private final MetadataStore metadataStore;
    private final VersionManager versionManager;
    private final DatasetValidationService validationService;
    private final RefreshLock refreshLock;
    private final RetryExecutor retryExecutor;
    private final DataCacheMetrics metrics;
    private final RefreshProgressRegistry progressRegistry;
    private final ResumableRefreshExecutor resumableRefreshExecutor;

    public RefreshCoordinator(DataCacheProperties properties, DremioSource dremioSource,
            SqlResourceLoader sqlResourceLoader, MetadataStore metadataStore, VersionManager versionManager,
            DatasetValidationService validationService, RefreshLock refreshLock, RetryExecutor retryExecutor,
            DataCacheMetrics metrics, RefreshProgressRegistry progressRegistry,
            ResumableRefreshExecutor resumableRefreshExecutor) {
        this.properties = properties;
        this.dremioSource = dremioSource;
        this.sqlResourceLoader = sqlResourceLoader;
        this.metadataStore = metadataStore;
        this.versionManager = versionManager;
        this.validationService = validationService;
        this.refreshLock = refreshLock;
        this.retryExecutor = retryExecutor;
        this.metrics = metrics;
        this.progressRegistry = progressRegistry;
        this.resumableRefreshExecutor = resumableRefreshExecutor;
    }

    /** Same as {@link #refresh(String, RefreshTrigger)} with {@link RefreshTrigger#MANUAL}. */
    public DatasetRefreshResult refresh(String datasetName) {
        return refresh(datasetName, RefreshTrigger.MANUAL);
    }

    public DatasetRefreshResult refresh(String datasetName, RefreshTrigger trigger) {
        DatasetProperties config = properties.getDatasets().get(datasetName);
        if (config == null) {
            throw new DatasetNotFoundException(datasetName);
        }
        if (!config.isEnabled()) {
            return new DatasetRefreshResult(datasetName, RefreshOutcome.DISABLED, null, null, null, null, null, 0);
        }
        if (!refreshLock.tryAcquire(datasetName)) {
            log.info("event=dataset-refresh-already-running dataset={}", datasetName);
            return new DatasetRefreshResult(datasetName, RefreshOutcome.ALREADY_RUNNING, null,
                    versionManager.findActive(datasetName).map(DatasetVersion::version).orElse(null),
                    null, null, "A refresh for this dataset is already in progress", 0);
        }

        MDC.put("dataset", datasetName);
        try {
            RetryExecutor.Outcome<AttemptResult> outcome = retryExecutor.execute(datasetName, config.getRetry(),
                    () -> runOnce(datasetName, config, trigger));

            Long activeVersion = versionManager.findActive(datasetName).map(DatasetVersion::version).orElse(null);
            if (outcome.isSuccess()) {
                AttemptResult result = outcome.value();
                RefreshTimings t = result.timings();
                metrics.recordRefreshSuccess(datasetName, t);
                log.info("event=dataset-refresh-completed dataset={} version={} status=SUCCESS rows={} batches={} "
                        + "totalDurationMs={} dremioSetupMs={} dremioFirstBatchMs={} arrowTransferMs={} "
                        + "duckDbWriteMs={} validationMs={} activationMs={} averageRowsPerSecond={}",
                        datasetName, result.newVersion(), t.rowsLoaded(), t.batchesLoaded(), t.totalMs(),
                        t.dremioSetupMs(), t.timeToFirstBatchMs(), t.arrowTransferMs(), t.duckDbWriteMs(),
                        t.validationMs(), t.activationMs(), String.format("%.1f", t.rowsPerSecond()));
                return new DatasetRefreshResult(datasetName, RefreshOutcome.SUCCESS, result.newVersion(), activeVersion,
                        t, null, null, outcome.attemptsMade());
            } else {
                DataCacheException error = outcome.error();
                RefreshOutcome refreshOutcome = error instanceof ValidationFailedException
                        ? RefreshOutcome.VALIDATION_FAILED : RefreshOutcome.FAILED;
                metrics.recordRefreshFailure(datasetName, error.getErrorCode());
                RefreshProgress lastAttempt = progressRegistry.find(datasetName).orElse(null);
                boolean resumableDataset = lastAttempt != null && lastAttempt.totalChunks() != null;
                if (resumableDataset) {
                    log.warn("event=dataset-refresh-failed dataset={} failedVersion={} existingActiveVersion={} "
                            + "existingActivePreserved=true rowsCommitted={} completedChunks={} totalChunks={} "
                            + "failedChunk={} elapsedMs={} stage={} status=PAUSED_RETRYABLE errorCode={} error={}",
                            datasetName, lastAttempt.version(), activeVersion, lastAttempt.rowsProcessed(),
                            lastAttempt.completedChunks(), lastAttempt.totalChunks(), lastAttempt.failedChunk(),
                            lastAttempt.elapsedMs(), lastAttempt.currentStage(), error.getErrorCode(), error.getMessage());
                } else {
                    log.warn("event=dataset-refresh-failed dataset={} failedVersion={} existingActiveVersion={} "
                            + "existingActivePreserved=true rowsProcessed={} elapsedMs={} stage={} errorCode={} error={}",
                            datasetName,
                            lastAttempt != null ? lastAttempt.version() : null,
                            activeVersion,
                            lastAttempt != null ? lastAttempt.rowsProcessed() : 0L,
                            lastAttempt != null ? lastAttempt.elapsedMs() : 0L,
                            lastAttempt != null ? lastAttempt.currentStage() : RefreshStage.FAILED,
                            error.getErrorCode(), error.getMessage());
                }
                return new DatasetRefreshResult(datasetName, refreshOutcome, null, activeVersion, null,
                        error.getErrorCode(), error.getMessage(), outcome.attemptsMade());
            }
        } finally {
            MDC.remove("dataset");
            refreshLock.release(datasetName);
        }
    }

    private record AttemptResult(long newVersion, RefreshTimings timings) {
    }

    /**
     * Explicit admin-triggered resume: identical to {@link #refresh(String, RefreshTrigger)} except
     * it first confirms a resumable BUILDING manifest actually exists, so a caller who specifically
     * asked to "resume" gets a clear {@link RefreshOutcome#NO_RESUMABLE_BUILD} instead of
     * accidentally kicking off a brand-new full load when there is nothing to continue. Automatic
     * startup resume ({@link DataCacheStartupCoordinator}) does not depend on this method - it is
     * purely a convenience/discoverability entry point for operators.
     */
    public DatasetRefreshResult resume(String datasetName) {
        DatasetProperties config = properties.getDatasets().get(datasetName);
        if (config == null) {
            throw new DatasetNotFoundException(datasetName);
        }
        boolean resumableConfig = properties.getResume().isEnabled() && config.getResume().isEnabled();
        if (!resumableConfig || metadataStore.findBuildingManifest(datasetName).isEmpty()) {
            Long activeVersion = versionManager.findActive(datasetName).map(DatasetVersion::version).orElse(null);
            log.info("event=dataset-resume-nothing-to-resume dataset={}", datasetName);
            return new DatasetRefreshResult(datasetName, RefreshOutcome.NO_RESUMABLE_BUILD, null, activeVersion,
                    null, null, "No resumable BUILDING version found for dataset '" + datasetName + "'", 0);
        }
        return refresh(datasetName, RefreshTrigger.MANUAL);
    }

    /**
     * Admin-triggered abandonment: intentionally discards any partial BUILDING version for this
     * dataset (marking its manifest ABANDONED and its {@code dataset_versions} row FAILED, and
     * deleting the physical file) and then starts a brand-new BUILDING version from zero, exactly as
     * if resume were disabled for this one attempt. The current ACTIVE version is never touched -
     * queries keep being served by it throughout. Intended as a protected admin operation for cases
     * where an operator has determined the partial build should not be continued (e.g. a known-bad
     * source state) even though it would otherwise be technically resumable.
     */
    public DatasetRefreshResult restartRefresh(String datasetName) {
        DatasetProperties config = properties.getDatasets().get(datasetName);
        if (config == null) {
            throw new DatasetNotFoundException(datasetName);
        }
        if (!refreshLock.tryAcquire(datasetName)) {
            log.info("event=dataset-restart-refresh-already-running dataset={}", datasetName);
            return new DatasetRefreshResult(datasetName, RefreshOutcome.ALREADY_RUNNING, null,
                    versionManager.findActive(datasetName).map(DatasetVersion::version).orElse(null),
                    null, null, "A refresh for this dataset is already in progress", 0);
        }
        try {
            for (DatasetVersion building : metadataStore.findByState(datasetName, VersionState.BUILDING)) {
                log.warn("event=dataset-restart-refresh-abandoning-partial-build dataset={} version={}",
                        datasetName, building.version());
                metadataStore.updateManifestStatus(datasetName, building.version(), RefreshManifestStatus.ABANDONED);
                metadataStore.recordFailure(datasetName, building.version(), "ADMIN_RESTART",
                        "Partial BUILDING version intentionally abandoned via restart-refresh admin operation");
                deleteQuietly(building.filePath());
            }
        } finally {
            refreshLock.release(datasetName);
        }
        return refresh(datasetName, RefreshTrigger.MANUAL);
    }

    private String resolveTableName(String datasetName, DatasetProperties config) {
        return config.getTableName() != null && !config.getTableName().isBlank() ? config.getTableName() : datasetName;
    }

    private AttemptResult runOnce(String datasetName, DatasetProperties config, RefreshTrigger trigger) {
        boolean resumable = properties.getResume().isEnabled() && config.getResume().isEnabled();
        String tableName = resolveTableName(datasetName, config);
        if (resumable) {
            return runResumableOnce(datasetName, tableName, config, trigger);
        }
        return runSimpleOnce(datasetName, tableName, config, trigger);
    }

    // --- Resumable (chunked) path ----------------------------------------------------------------

    private AttemptResult runResumableOnce(String datasetName, String tableName, DatasetProperties config,
            RefreshTrigger trigger) {
        long totalStartNanos = System.nanoTime();

        // Best-guess version for progress-registry purposes only; ResumableRefreshExecutor corrects
        // it via RefreshProgress.updateVersion(...) once the real resume-vs-fresh decision is made.
        long guessedVersion = metadataStore.findBuildingManifest(datasetName)
                .map(RefreshManifest::version)
                .orElseGet(() -> metadataStore.nextVersionNumber(datasetName));

        Optional<DatasetVersion> priorActive = versionManager.findActive(datasetName);
        Long previousVersionRowCount = priorActive.map(DatasetVersion::rowCount).orElse(null);
        Long expectedRowCount = config.getProgress().getExpectedRowCount();
        RefreshProgress progress = progressRegistry.start(datasetName, guessedVersion, trigger, previousVersionRowCount,
                expectedRowCount);

        log.info("event=dataset-refresh-started dataset={} version={} trigger={} startupRefresh={} status=BUILDING resumable=true",
                datasetName, guessedVersion, trigger, trigger == RefreshTrigger.STARTUP);

        try {
            ResumableRefreshExecutor.LoadResult result = resumableRefreshExecutor.load(datasetName, tableName, config, progress);
            log.info("event=dataset-load-completed dataset={} version={} rowsProcessed={} batchesProcessed={} loadDurationMs={} resumed={}",
                    datasetName, result.version(), result.rowCount(), progress.batchesProcessed(), progress.elapsedMs(),
                    result.resumed());
            metadataStore.recordLoadStats(datasetName, result.version(), result.rowCount(), fileSizeQuietly(result.buildingPath()));
            metadataStore.updateState(datasetName, result.version(), VersionState.VALIDATING);
            progress.stage(RefreshStage.VALIDATING);
            return validateAndActivate(datasetName, result.version(), tableName, result.buildingPath(), result.rowCount(),
                    result.columnNames(), config, progress, priorActive, totalStartNanos, result.dremioSetupNanos(),
                    result.firstBatchNanos(), result.arrowTransferNanos(), result.duckDbWriteNanos());
        } catch (DataCacheException e) {
            progress.stage(RefreshStage.FAILED);
            throw e;
        } catch (RuntimeException e) {
            progress.stage(RefreshStage.FAILED);
            throw new DataCacheException("UNEXPECTED_ERROR", "Unexpected refresh failure: " + e.getMessage(), true, e);
        }
    }

    // --- Simple (single continuous stream) path ----------------------------------------------------

    private AttemptResult runSimpleOnce(String datasetName, String tableName, DatasetProperties config, RefreshTrigger trigger) {
        long totalStartNanos = System.nanoTime();
        String sourceSql = sqlResourceLoader.load(config.getSourceSql());
        String sqlHash = Sha256.hex(sourceSql);

        DuckDbProperties duckDbProperties = properties.getDuckdb();
        long version = metadataStore.nextVersionNumber(datasetName);
        Path buildingPath = datasetDirectory(duckDbProperties, datasetName)
                .resolve(tableName + "_v" + version + "_building.duckdb");
        metadataStore.createBuildingVersion(datasetName, version, buildingPath, tableName, sqlHash);

        Optional<DatasetVersion> priorActive = versionManager.findActive(datasetName);
        Long previousVersionRowCount = priorActive.map(DatasetVersion::rowCount).orElse(null);
        Long expectedRowCount = config.getProgress().getExpectedRowCount();
        RefreshProgress progress = progressRegistry.start(datasetName, version, trigger, previousVersionRowCount,
                expectedRowCount);
        ProgressLoggingProperties progressLogging = properties.getLogging().getProgress();

        log.info("event=dataset-refresh-started dataset={} version={} trigger={} startupRefresh={} status=BUILDING",
                datasetName, version, trigger, trigger == RefreshTrigger.STARTUP);

        try {
            long dremioSetupNanos;
            long firstBatchNanos = 0;
            long arrowTransferNanos = 0;
            long duckDbWriteNanos = 0;

            progress.stage(RefreshStage.CONNECTING_DREMIO);
            log.info("event=dremio-query-started dataset={} version={} sourceSql={}",
                    datasetName, version, config.getSourceSql());

            long setupStart = System.nanoTime();
            try (ArrowBatchStream stream = dremioSource.executeQuery(sourceSql);
                    DuckDbDatasetWriter writer = new DuckDbDatasetWriter(buildingPath, datasetName, tableName, duckDbProperties)) {
                dremioSetupNanos = System.nanoTime() - setupStart;
                progress.stage(RefreshStage.WAITING_FOR_FIRST_BATCH);

                writer.begin(stream.schema());

                boolean firstBatch = true;
                long firstBatchWaitStart = System.nanoTime();
                while (true) {
                    long beforeNext = System.nanoTime();
                    boolean hasBatch = stream.loadNextBatch();
                    long afterNext = System.nanoTime();
                    if (firstBatch) {
                        firstBatchNanos = afterNext - firstBatchWaitStart;
                        firstBatch = false;
                        if (hasBatch) {
                            long timeToFirstBatchMs = nanosToMs(firstBatchNanos);
                            progress.recordFirstBatch(timeToFirstBatchMs);
                            log.info("event=dremio-first-batch dataset={} version={} timeToFirstBatchMs={} columns={}",
                                    datasetName, version, timeToFirstBatchMs, stream.schema().getFields().size());
                            progress.stage(RefreshStage.STREAMING);
                        }
                    }
                    if (!hasBatch) {
                        break;
                    }
                    arrowTransferNanos += (afterNext - beforeNext);

                    VectorSchemaRoot batch = stream.currentBatch();
                    int rowsInBatch = batch.getRowCount();
                    long beforeWrite = System.nanoTime();
                    long bytesInBatch = writer.writeBatch(batch);
                    duckDbWriteNanos += (System.nanoTime() - beforeWrite);

                    // Only counted after the batch has actually been persisted to DuckDB - never
                    // speculatively before, and never estimated from the batch count alone.
                    progress.recordBatch(rowsInBatch, bytesInBatch);
                    RefreshProgressLogger.logIfDue(progress, progressLogging, () -> fileSizeQuietly(buildingPath));
                }

                long finishStart = System.nanoTime();
                long rowCount = writer.finish();
                duckDbWriteNanos += (System.nanoTime() - finishStart);

                long bytesLoaded = fileSizeQuietly(buildingPath);
                metadataStore.recordLoadStats(datasetName, version, rowCount, bytesLoaded);
                metadataStore.updateState(datasetName, version, VersionState.VALIDATING);
                progress.stage(RefreshStage.VALIDATING);
                log.info("event=dataset-load-completed dataset={} version={} rowsProcessed={} batchesProcessed={} loadDurationMs={}",
                        datasetName, version, rowCount, progress.batchesProcessed(), progress.elapsedMs());

                return validateAndActivate(datasetName, version, tableName, buildingPath, rowCount, writer.columnNames(),
                        config, progress, priorActive, totalStartNanos, dremioSetupNanos, firstBatchNanos,
                        arrowTransferNanos, duckDbWriteNanos);
            }
        } catch (DataCacheException e) {
            progress.stage(RefreshStage.FAILED);
            metadataStore.recordFailure(datasetName, version, e.getErrorCode(), e.getMessage());
            deleteQuietly(buildingPath);
            throw e;
        } catch (RuntimeException e) {
            progress.stage(RefreshStage.FAILED);
            metadataStore.recordFailure(datasetName, version, "UNEXPECTED_ERROR", String.valueOf(e.getMessage()));
            deleteQuietly(buildingPath);
            throw new DataCacheException("UNEXPECTED_ERROR", "Unexpected refresh failure: " + e.getMessage(), true, e);
        }
    }

    /**
     * Shared by both load paths: run configured validations, and - only if they all pass -
     * atomically activate the new version. A validation failure here is handled by each caller's
     * own catch block (it throws {@link ValidationFailedException}, a {@link DataCacheException}).
     */
    private AttemptResult validateAndActivate(String datasetName, long version, String tableName, Path buildingPath,
            long rowCount, List<String> columnNames, DatasetProperties config, RefreshProgress progress,
            Optional<DatasetVersion> priorActive, long totalStartNanos, long dremioSetupNanos, long firstBatchNanos,
            long arrowTransferNanos, long duckDbWriteNanos) {
        log.info("event=dataset-validation-started dataset={} version={}", datasetName, version);
        long validationStart = System.nanoTime();
        ValidationOutcome validationOutcome = validationService.validate(datasetName, buildingPath, tableName,
                rowCount, columnNames, config.getValidation());
        long validationNanos = System.nanoTime() - validationStart;
        metadataStore.recordValidation(datasetName, version,
                validationOutcome.passed() ? ValidationStatus.PASSED : ValidationStatus.FAILED);
        for (ValidationOutcome.CheckResult check : validationOutcome.checks()) {
            log.info("event=dataset-validation dataset={} version={} validation={} status={} detail={}",
                    datasetName, version, check.name(), check.passed() ? "PASS" : "FAIL", check.detail());
        }

        if (!validationOutcome.passed()) {
            throw new ValidationFailedException("Dataset '" + datasetName + "' version " + version
                    + " failed validation: " + validationOutcome.summarize());
        }

        progress.stage(RefreshStage.ACTIVATING);
        long activationStart = System.nanoTime();
        DuckDbProperties duckDbProperties = properties.getDuckdb();
        Path finalPath = datasetDirectory(duckDbProperties, datasetName)
                .resolve(tableName + "_v" + version + ".duckdb");
        moveFile(buildingPath, finalPath);
        metadataStore.updateFilePath(datasetName, version, finalPath);
        long bytesLoaded = fileSizeQuietly(finalPath);
        long totalMs = nanosToMs(System.nanoTime() - totalStartNanos);
        metadataStore.recordCompletion(datasetName, version, totalMs);
        Long previousActiveVersion = priorActive.map(DatasetVersion::version).orElse(null);
        versionManager.activate(datasetName, version);
        log.info("event=dataset-version-activated dataset={} newVersion={} previousVersion={} rowCount={}",
                datasetName, version, previousActiveVersion, rowCount);
        progress.stage(RefreshStage.CLEANING_UP);
        long activationNanos = System.nanoTime() - activationStart;
        progress.stage(RefreshStage.COMPLETED);

        RefreshTimings timings = new RefreshTimings(
                nanosToMs(dremioSetupNanos),
                nanosToMs(firstBatchNanos),
                nanosToMs(arrowTransferNanos),
                nanosToMs(duckDbWriteNanos),
                nanosToMs(validationNanos),
                nanosToMs(activationNanos),
                totalMs,
                rowCount,
                bytesLoaded,
                progress.batchesProcessed());
        return new AttemptResult(version, timings);
    }

    public static Path datasetDirectory(DuckDbProperties properties, String datasetName) {
        return Path.of(properties.getBaseDirectory(), datasetName);
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void moveFile(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DataCacheException("DUCKDB_WRITE_ERROR", "Failed to finalize dataset file " + to, true, e);
        }
    }

    private static long fileSizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("event=building-file-cleanup-failed path={}", path, e);
        }
    }
}

package com.enterprise.datacache.refresh;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.duckdb.DuckDbDatasetWriter;
import com.enterprise.datacache.exception.DataCacheException;
import com.enterprise.datacache.exception.DatasetNotFoundException;
import com.enterprise.datacache.exception.ValidationFailedException;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.metrics.DataCacheMetrics;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.model.RefreshTimings;
import com.enterprise.datacache.model.ValidationStatus;
import com.enterprise.datacache.spi.ArrowBatchStream;
import com.enterprise.datacache.spi.DremioSource;
import com.enterprise.datacache.util.Sha256;
import com.enterprise.datacache.util.SqlResourceLoader;
import com.enterprise.datacache.validation.DatasetValidationService;
import com.enterprise.datacache.validation.ValidationOutcome;
import com.enterprise.datacache.version.VersionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Orchestrates the full lifecycle of one dataset refresh: acquire the per-dataset lock, stream
 * Dremio Arrow batches into a new BUILDING DuckDB file with fine-grained timing instrumentation,
 * validate, and atomically activate - all wrapped in the dataset's configured retry policy.
 * Concurrency across different datasets is bounded by the caller (see
 * {@code DataCacheRefreshServiceImpl}), not by this class.
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

    public RefreshCoordinator(DataCacheProperties properties, DremioSource dremioSource,
            SqlResourceLoader sqlResourceLoader, MetadataStore metadataStore, VersionManager versionManager,
            DatasetValidationService validationService, RefreshLock refreshLock, RetryExecutor retryExecutor,
            DataCacheMetrics metrics) {
        this.properties = properties;
        this.dremioSource = dremioSource;
        this.sqlResourceLoader = sqlResourceLoader;
        this.metadataStore = metadataStore;
        this.versionManager = versionManager;
        this.validationService = validationService;
        this.refreshLock = refreshLock;
        this.retryExecutor = retryExecutor;
        this.metrics = metrics;
    }

    public DatasetRefreshResult refresh(String datasetName) {
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
                    () -> runOnce(datasetName, config));

            Long activeVersion = versionManager.findActive(datasetName).map(DatasetVersion::version).orElse(null);
            if (outcome.isSuccess()) {
                AttemptResult result = outcome.value();
                metrics.recordRefreshSuccess(datasetName, result.timings());
                log.info("event=dataset-refresh-completed dataset={} version={} rows={} durationMs={} rowsPerSecond={} status=SUCCESS",
                        datasetName, result.newVersion(), result.timings().rowsLoaded(), result.timings().totalMs(),
                        String.format("%.1f", result.timings().rowsPerSecond()));
                return new DatasetRefreshResult(datasetName, RefreshOutcome.SUCCESS, result.newVersion(), activeVersion,
                        result.timings(), null, null, outcome.attemptsMade());
            } else {
                DataCacheException error = outcome.error();
                RefreshOutcome refreshOutcome = error instanceof ValidationFailedException
                        ? RefreshOutcome.VALIDATION_FAILED : RefreshOutcome.FAILED;
                metrics.recordRefreshFailure(datasetName, error.getErrorCode());
                log.warn("event=dataset-refresh-completed dataset={} status={} errorCode={} error={}",
                        datasetName, refreshOutcome, error.getErrorCode(), error.getMessage());
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

    private AttemptResult runOnce(String datasetName, DatasetProperties config) {
        long totalStartNanos = System.nanoTime();
        String tableName = config.getTableName() != null && !config.getTableName().isBlank()
                ? config.getTableName() : datasetName;
        String sourceSql = sqlResourceLoader.load(config.getSourceSql());
        String sqlHash = Sha256.hex(sourceSql);

        DuckDbProperties duckDbProperties = properties.getDuckdb();
        long version = metadataStore.nextVersionNumber(datasetName);
        Path buildingPath = datasetDirectory(duckDbProperties, datasetName)
                .resolve(tableName + "_v" + version + "_building.duckdb");
        metadataStore.createBuildingVersion(datasetName, version, buildingPath, tableName, sqlHash);

        try {
            long dremioSetupNanos;
            long firstBatchNanos = 0;
            long arrowTransferNanos = 0;
            long duckDbWriteNanos = 0;

            long setupStart = System.nanoTime();
            try (ArrowBatchStream stream = dremioSource.executeQuery(sourceSql);
                    DuckDbDatasetWriter writer = new DuckDbDatasetWriter(buildingPath, tableName, duckDbProperties)) {
                dremioSetupNanos = System.nanoTime() - setupStart;

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
                    }
                    if (!hasBatch) {
                        break;
                    }
                    arrowTransferNanos += (afterNext - beforeNext);

                    long beforeWrite = System.nanoTime();
                    writer.writeBatch(stream.currentBatch());
                    duckDbWriteNanos += (System.nanoTime() - beforeWrite);
                }

                long finishStart = System.nanoTime();
                long rowCount = writer.finish();
                duckDbWriteNanos += (System.nanoTime() - finishStart);

                long bytesLoaded = fileSizeQuietly(buildingPath);
                metadataStore.recordLoadStats(datasetName, version, rowCount, bytesLoaded);
                metadataStore.updateState(datasetName, version, com.enterprise.datacache.model.VersionState.VALIDATING);

                long validationStart = System.nanoTime();
                ValidationOutcome validationOutcome = validationService.validate(datasetName, buildingPath, tableName,
                        rowCount, writer.columnNames(), config.getValidation());
                long validationNanos = System.nanoTime() - validationStart;
                metadataStore.recordValidation(datasetName, version,
                        validationOutcome.passed() ? ValidationStatus.PASSED : ValidationStatus.FAILED);

                if (!validationOutcome.passed()) {
                    throw new ValidationFailedException("Dataset '" + datasetName + "' version " + version
                            + " failed validation: " + validationOutcome.summarize());
                }

                long activationStart = System.nanoTime();
                Path finalPath = datasetDirectory(duckDbProperties, datasetName)
                        .resolve(tableName + "_v" + version + ".duckdb");
                moveFile(buildingPath, finalPath);
                metadataStore.updateFilePath(datasetName, version, finalPath);
                long totalMs = nanosToMs(System.nanoTime() - totalStartNanos);
                metadataStore.recordCompletion(datasetName, version, totalMs);
                versionManager.activate(datasetName, version);
                long activationNanos = System.nanoTime() - activationStart;

                RefreshTimings timings = new RefreshTimings(
                        nanosToMs(dremioSetupNanos),
                        nanosToMs(firstBatchNanos),
                        nanosToMs(arrowTransferNanos),
                        nanosToMs(duckDbWriteNanos),
                        nanosToMs(validationNanos),
                        nanosToMs(activationNanos),
                        totalMs,
                        rowCount,
                        bytesLoaded);
                return new AttemptResult(version, timings);
            }
        } catch (DataCacheException e) {
            metadataStore.recordFailure(datasetName, version, e.getErrorCode(), e.getMessage());
            deleteQuietly(buildingPath);
            throw e;
        } catch (RuntimeException e) {
            metadataStore.recordFailure(datasetName, version, "UNEXPECTED_ERROR", String.valueOf(e.getMessage()));
            deleteQuietly(buildingPath);
            throw new DataCacheException("UNEXPECTED_ERROR", "Unexpected refresh failure: " + e.getMessage(), true, e);
        }
    }

    static Path datasetDirectory(DuckDbProperties properties, String datasetName) {
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

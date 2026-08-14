package com.enterprise.datacache.feature.cache.health;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.exception.DatasetNotFoundException;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.model.ChunkStatus;
import com.enterprise.datacache.feature.cache.model.DatasetChunk;
import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import com.enterprise.datacache.feature.cache.model.DatasetVersion;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import com.enterprise.datacache.feature.cache.model.RefreshStage;
import com.enterprise.datacache.feature.cache.model.VersionState;
import com.enterprise.datacache.feature.cache.refresh.RefreshLock;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgress;
import com.enterprise.datacache.feature.cache.refresh.RefreshProgressRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DataCacheStatusServiceImpl implements DataCacheStatusService {

    private final DataCacheProperties properties;
    private final MetadataStore metadataStore;
    private final RefreshLock refreshLock;
    private final RefreshProgressRegistry progressRegistry;

    public DataCacheStatusServiceImpl(DataCacheProperties properties, MetadataStore metadataStore,
            RefreshLock refreshLock, RefreshProgressRegistry progressRegistry) {
        this.properties = properties;
        this.metadataStore = metadataStore;
        this.refreshLock = refreshLock;
        this.progressRegistry = progressRegistry;
    }

    @Override
    public DatasetStatus getStatus(String datasetName) {
        DatasetProperties config = properties.getDatasets().get(datasetName);
        if (config == null) {
            throw new DatasetNotFoundException(datasetName);
        }

        List<DatasetVersion> versions = metadataStore.findAll(datasetName); // ordered version DESC
        Optional<DatasetVersion> active = versions.stream().filter(v -> v.state() == VersionState.ACTIVE).findFirst();
        Optional<DatasetVersion> previous = versions.stream().filter(v -> v.state() == VersionState.PREVIOUS).findFirst();
        Optional<DatasetVersion> mostRecentAttempt = versions.stream().findFirst();

        boolean refreshInProgress = refreshLock.isRunning(datasetName);
        boolean resumable = properties.getResume().isEnabled() && config.getResume().isEnabled();

        RefreshOutcome lastStatus = mostRecentAttempt.map(v -> classify(v, refreshInProgress)).orElse(RefreshOutcome.NEVER_RUN);
        String lastError = mostRecentAttempt
                .filter(v -> v.state() == VersionState.FAILED)
                .map(DatasetVersion::errorSummary)
                .orElse(null);

        // Live progress fields are populated only while a refresh is actually in flight - once it
        // finishes, RefreshProgress may still be in the registry (for metrics/history), but the
        // status API should not present stale "in progress" figures for a dataset that's idle.
        Long buildingVersion = null;
        RefreshStage refreshStage = null;
        Long rowsProcessed = null;
        Long batchesProcessed = null;
        Long elapsedMs = null;
        Double averageRowsPerSecond = null;
        Double estimatedPercent = null;
        Boolean resuming = null;
        Long completedChunks = null;
        Long totalChunks = null;
        Long rowsCommitted = null;
        Long currentChunk = null;
        Long failedChunk = null;
        String sourceSnapshotId = null;
        boolean pausedRetryable = false;

        if (refreshInProgress) {
            Optional<RefreshProgress> progress = progressRegistry.find(datasetName);
            if (progress.isPresent()) {
                RefreshProgress p = progress.get();
                buildingVersion = p.version();
                refreshStage = p.currentStage();
                rowsProcessed = p.rowsProcessed();
                batchesProcessed = p.batchesProcessed();
                elapsedMs = p.elapsedMs();
                averageRowsPerSecond = p.averageRowsPerSecond();
                estimatedPercent = p.estimatedPercent();
                if (p.totalChunks() != null) {
                    resuming = p.isResuming();
                    completedChunks = p.completedChunks();
                    totalChunks = p.totalChunks();
                    rowsCommitted = p.rowsProcessed(); // recordBatch() only fires on a durable chunk commit
                    currentChunk = p.currentChunk();
                    failedChunk = p.failedChunk();
                }
            }
        } else if (resumable) {
            // No refresh is actively running right now, but a BUILDING manifest can still exist -
            // preserved across a crash/restart by StartupRecoveryService, or left paused after its
            // retry budget was exhausted - waiting to be resumed automatically or via the admin
            // /resume endpoint. Pulled from persisted metadata since there is no live RefreshProgress
            // for it.
            Optional<RefreshManifest> manifest = metadataStore.findBuildingManifest(datasetName);
            if (manifest.isPresent()) {
                RefreshManifest m = manifest.get();
                pausedRetryable = true;
                buildingVersion = m.version();
                completedChunks = m.completedChunks();
                totalChunks = m.totalChunks();
                rowsCommitted = m.rowsCommitted();
                sourceSnapshotId = m.sourceSnapshotId();
                List<DatasetChunk> chunks = metadataStore.findChunks(datasetName, m.version());
                failedChunk = chunks.stream().filter(c -> c.status() == ChunkStatus.FAILED)
                        .map(DatasetChunk::chunkId).findFirst().orElse(null);
            }
        }

        return new DatasetStatus(
                datasetName,
                config.isEnabled(),
                active.map(DatasetVersion::version).orElse(null),
                previous.map(DatasetVersion::version).orElse(null),
                active.map(DatasetVersion::rowCount).orElse(null),
                active.map(DatasetVersion::completedAt).orElse(null),
                lastStatus,
                mostRecentAttempt.map(DatasetVersion::durationMs).orElse(null),
                lastError,
                refreshInProgress,
                buildingVersion,
                refreshStage,
                rowsProcessed,
                batchesProcessed,
                elapsedMs,
                averageRowsPerSecond,
                estimatedPercent,
                resumable,
                resuming,
                completedChunks,
                totalChunks,
                rowsCommitted,
                currentChunk,
                failedChunk,
                sourceSnapshotId,
                pausedRetryable);
    }

    private RefreshOutcome classify(DatasetVersion version, boolean refreshInProgress) {
        return switch (version.state()) {
            case ACTIVE, PREVIOUS -> RefreshOutcome.SUCCESS;
            case FAILED -> "VALIDATION_FAILED".equals(version.errorCode()) ? RefreshOutcome.VALIDATION_FAILED : RefreshOutcome.FAILED;
            case BUILDING, VALIDATING ->
                // A currently-running attempt is not yet a completed outcome at all - only report
                // PAUSED_RETRYABLE for a version that is genuinely sitting idle (a resumable partial
                // build preserved across a crash/restart, or one waiting for its next retry), never
                // for a version actively being worked on by this process right now.
                refreshInProgress ? RefreshOutcome.FAILED : RefreshOutcome.PAUSED_RETRYABLE;
        };
    }

    @Override
    public Map<String, DatasetStatus> getAllStatuses() {
        Map<String, DatasetStatus> result = new LinkedHashMap<>();
        for (String name : properties.getDatasets().keySet()) {
            result.put(name, getStatus(name));
        }
        return result;
    }
}

package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.StartupExecutionMode;
import com.enterprise.datacache.feature.cache.config.StartupMode;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides, per enabled dataset, what must happen at startup - and drives it - after
 * {@link StartupRecoveryService} has reconciled metadata against physical storage and before
 * {@link DynamicRefreshScheduler} registers cron triggers. This is the only place startup-time
 * refresh decisions are made; it triggers work exclusively through
 * {@link DataCacheRefreshService}, the same pipeline used by the scheduler and the admin REST/Java
 * API, so per-dataset locking ({@link RefreshLock}) and retry/validation behave identically
 * regardless of what triggered the refresh.
 *
 * <p>Rules (see docs/23-STARTUP-CACHE-LIFECYCLE.md for the full rationale):
 * <ul>
 *   <li>No ACTIVE version exists: an initial load is triggered automatically, regardless of
 *       {@link StartupMode} - a dataset is never left permanently empty.</li>
 *   <li>{@link StartupMode#USE_EXISTING_OR_CREATE} with an existing ACTIVE version: reused as-is,
 *       no startup refresh - UNLESS {@link StartupRecoveryService} preserved a resumable BUILDING
 *       version for this dataset (a partial load interrupted by the previous crash/restart), in
 *       which case a background resume is triggered automatically. ACTIVE keeps serving queries
 *       throughout; automatic resume is mandatory here specifically so that manual intervention is
 *       never required to continue a partial load.</li>
 *   <li>{@link StartupMode#ALWAYS_REFRESH} with an existing ACTIVE version: made available
 *       immediately, and a new version is built in the background through the normal refresh
 *       pipeline (never a destructive delete-then-reload). If a resumable BUILDING version already
 *       exists, the same background refresh call resumes it instead of starting a new one - see
 *       {@code ResumableRefreshExecutor#resolveManifest}, which always prefers a compatible existing
 *       BUILDING manifest over allocating a new version, so this never creates a parallel duplicate
 *       version alongside a resumable one.</li>
 *   <li>No ACTIVE version exists at all (including the very first load of a dataset): the initial
 *       load triggered below resumes a preserved partial BUILDING version the same way, through the
 *       same {@code resolveManifest} preference - a first load interrupted at 20 of 60 million rows
 *       continues from its last completed chunk rather than restarting at zero.</li>
 * </ul>
 */
public class DataCacheStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DataCacheStartupCoordinator.class);

    private final DataCacheProperties properties;
    private final VersionManager versionManager;
    private final DataCacheRefreshService refreshService;
    private final MetadataStore metadataStore;

    public DataCacheStartupCoordinator(DataCacheProperties properties, VersionManager versionManager,
            DataCacheRefreshService refreshService, MetadataStore metadataStore) {
        this.properties = properties;
        this.versionManager = versionManager;
        this.refreshService = refreshService;
        this.metadataStore = metadataStore;
    }

    /**
     * Evaluates every enabled dataset and triggers whatever startup work is required. If
     * {@code data-cache.startup.execution-mode} is {@code BLOCK_UNTIL_REQUIRED_CACHE_READY}, blocks
     * the calling thread (bounded by {@code data-cache.startup.timeout}) until every dataset marked
     * {@code required: true} that needed an initial load has finished - success or failure. Never
     * blocks indefinitely: on timeout, the wait simply ends and loading continues in the
     * background.
     */
    public void runStartupSequence() {
        List<CompletableFuture<DatasetRefreshResult>> requiredInitialLoads = new ArrayList<>();

        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            String datasetName = entry.getKey();
            DatasetProperties config = entry.getValue();
            if (!config.isEnabled()) {
                continue;
            }

            boolean hasActiveVersion = versionManager.findActive(datasetName).isPresent();
            StartupMode mode = config.getStartup().getMode();

            if (!hasActiveVersion) {
                log.info("event=startup-initial-load-triggered dataset={} reason=no-active-version required={}",
                        datasetName, config.isRequired());
                CompletableFuture<DatasetRefreshResult> future = refreshService.refreshAsync(datasetName, RefreshTrigger.STARTUP);
                if (config.isRequired()) {
                    requiredInitialLoads.add(future);
                }
            } else if (mode == StartupMode.ALWAYS_REFRESH) {
                log.info("event=startup-background-refresh-triggered dataset={} existingActiveAvailableImmediately=true",
                        datasetName);
                // Fire-and-forget: the existing ACTIVE version already serves queries, so this
                // never needs to be part of the required-readiness wait below. If a resumable
                // BUILDING version survived StartupRecoveryService, resolveManifest() inside
                // ResumableRefreshExecutor resumes it instead of allocating a new version.
                refreshService.refreshAsync(datasetName, RefreshTrigger.STARTUP);
            } else if (hasResumableBuildingVersion(datasetName, config)) {
                log.info("event=startup-resume-triggered dataset={} mode={} existingActiveAvailableImmediately=true",
                        datasetName, mode);
                // USE_EXISTING_OR_CREATE normally leaves an existing ACTIVE version untouched, but a
                // resumable partial BUILDING version left over from a crash/restart must never sit
                // forever unresumed - automatic resume is mandatory, not something an operator has
                // to trigger manually. ACTIVE keeps serving queries throughout.
                refreshService.refreshAsync(datasetName, RefreshTrigger.STARTUP);
            } else {
                log.info("event=startup-reuse-existing-active dataset={} mode={}", datasetName, mode);
            }
        }

        if (properties.getStartup().getExecutionMode() == StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY
                && !requiredInitialLoads.isEmpty()) {
            waitForRequiredDatasets(requiredInitialLoads);
        }
    }

    private void waitForRequiredDatasets(List<CompletableFuture<DatasetRefreshResult>> futures) {
        Duration timeout = properties.getStartup().getTimeout();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        long startNanos = System.nanoTime();
        try {
            all.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("event=startup-blocking-wait-completed requiredDatasets={} waitedMs={}", futures.size(),
                    elapsedMs(startNanos));
        } catch (TimeoutException e) {
            log.warn("event=startup-blocking-wait-timed-out timeoutMs={} requiredDatasets={}", timeout.toMillis(),
                    futures.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=startup-blocking-wait-interrupted");
        } catch (ExecutionException e) {
            // Individual dataset failures are already persisted via metadata/status; the readiness
            // indicator reflects them. There is nothing further to do here.
            log.warn("event=startup-blocking-wait-completed-with-failures waitedMs={}", elapsedMs(startNanos), e);
        }
    }

    /**
     * True only if this dataset has a live BUILDING resume manifest AND resume is still enabled
     * (globally and for this dataset). {@link StartupRecoveryService} already discarded any BUILDING
     * version that was not safely resumable (corrupt, expired, incompatible, or resume disabled) -
     * so a BUILDING manifest surviving to this point is, by construction, safe to hand to
     * {@code refreshAsync}, which resumes it via {@code ResumableRefreshExecutor#resolveManifest}
     * rather than starting a new version.
     */
    private boolean hasResumableBuildingVersion(String datasetName, DatasetProperties config) {
        return properties.getResume().isEnabled() && config.getResume().isEnabled()
                && metadataStore.findBuildingManifest(datasetName).isPresent();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}

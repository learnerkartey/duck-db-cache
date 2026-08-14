package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.StartupExecutionMode;
import com.enterprise.datacache.feature.cache.config.StartupMode;
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
 *       no startup refresh.</li>
 *   <li>{@link StartupMode#ALWAYS_REFRESH} with an existing ACTIVE version: made available
 *       immediately, and a new version is built in the background through the normal refresh
 *       pipeline (never a destructive delete-then-reload).</li>
 * </ul>
 */
public class DataCacheStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DataCacheStartupCoordinator.class);

    private final DataCacheProperties properties;
    private final VersionManager versionManager;
    private final DataCacheRefreshService refreshService;

    public DataCacheStartupCoordinator(DataCacheProperties properties, VersionManager versionManager,
            DataCacheRefreshService refreshService) {
        this.properties = properties;
        this.versionManager = versionManager;
        this.refreshService = refreshService;
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
                // never needs to be part of the required-readiness wait below.
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

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}

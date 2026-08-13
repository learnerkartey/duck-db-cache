package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds all refresh concurrency - manual, scheduled, and bulk - to a single fixed-size executor
 * sized by {@code data-cache.refresh.max-concurrent-datasets}, so datasets never contend for
 * unbounded threads regardless of how many trigger sources fire at once.
 */
public class DataCacheRefreshServiceImpl implements DataCacheRefreshService {

    private static final Logger log = LoggerFactory.getLogger(DataCacheRefreshServiceImpl.class);

    private final RefreshCoordinator coordinator;
    private final DataCacheProperties properties;
    private final ExecutorService executor;

    public DataCacheRefreshServiceImpl(RefreshCoordinator coordinator, DataCacheProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getRefresh().getMaxConcurrentDatasets()), refreshThreadFactory());
    }

    private static ThreadFactory refreshThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "data-cache-refresh-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public DatasetRefreshResult refresh(String datasetName) {
        try {
            return executor.submit(() -> coordinator.refresh(datasetName)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DatasetRefreshResult(datasetName, RefreshOutcome.FAILED, null, null, null,
                    "INTERRUPTED", "Refresh was interrupted", 0);
        } catch (java.util.concurrent.ExecutionException e) {
            throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
        }
    }

    @Override
    public CompletableFuture<DatasetRefreshResult> refreshAsync(String datasetName) {
        return CompletableFuture.supplyAsync(() -> coordinator.refresh(datasetName), executor);
    }

    @Override
    public Map<String, DatasetRefreshResult> refreshAll() {
        Map<String, CompletableFuture<DatasetRefreshResult>> futures = new LinkedHashMap<>();
        properties.getDatasets().forEach((name, config) -> {
            if (config.isEnabled()) {
                futures.put(name, refreshAsync(name));
            }
        });
        Map<String, DatasetRefreshResult> results = new LinkedHashMap<>();
        futures.forEach((name, future) -> results.put(name, future.join()));
        return results;
    }

    public void shutdown() {
        log.info("event=refresh-executor-shutdown");
        executor.shutdown();
    }
}

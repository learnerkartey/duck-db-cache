package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.util.StringUtils;

/**
 * Registers exactly one cron-triggered refresh task per enabled, cron-configured dataset - driven
 * entirely by {@code data-cache.datasets.*.refresh-cron}. Adding a new dataset to configuration is
 * enough to get its own independent schedule; no new scheduler method is ever required.
 */
public class DynamicRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DynamicRefreshScheduler.class);

    private final TaskScheduler taskScheduler;
    private final DataCacheProperties properties;
    private final Consumer<String> refreshTrigger;
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    public DynamicRefreshScheduler(TaskScheduler taskScheduler, DataCacheProperties properties,
            Consumer<String> refreshTrigger) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.refreshTrigger = refreshTrigger;
    }

    public synchronized void start() {
        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            String datasetName = entry.getKey();
            DatasetProperties config = entry.getValue();
            if (!config.isEnabled() || !StringUtils.hasText(config.getRefreshCron())) {
                continue;
            }
            CronTrigger trigger = new CronTrigger(config.getRefreshCron());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                log.info("event=scheduled-refresh-fired dataset={}", datasetName);
                refreshTrigger.accept(datasetName);
            }, trigger);
            scheduledTasks.add(future);
            log.info("event=dataset-schedule-registered dataset={} cron={}", datasetName, config.getRefreshCron());
        }
    }

    public synchronized void stop() {
        for (ScheduledFuture<?> future : scheduledTasks) {
            future.cancel(false);
        }
        scheduledTasks.clear();
    }
}

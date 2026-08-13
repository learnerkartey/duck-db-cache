package com.enterprise.datacache.feature.cache.health;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.StartupExecutionMode;
import com.enterprise.datacache.feature.cache.model.DatasetAvailability;
import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Gates the readiness probe on required datasets actually being available, but only when
 * {@code data-cache.startup.execution-mode} is {@code BLOCK_UNTIL_REQUIRED_CACHE_READY}. In the
 * default {@code ASYNC} mode this always reports UP - the application is meant to become ready
 * immediately and let individual queries fail cleanly while a dataset is still loading, rather
 * than holding up the whole application's readiness.
 *
 * <p>Intended to be added to the Actuator "readiness" health group, e.g.
 * {@code management.endpoint.health.group.readiness.include=readinessState,dataCacheReadiness}
 * (see docs/20-OPENSHIFT-DEPLOYMENT.md). Bean name {@code dataCacheReadinessHealthIndicator}
 * exposes this as the {@code dataCacheReadiness} health component.
 */
public class DataCacheReadinessIndicator implements HealthIndicator {

    private final DataCacheProperties properties;
    private final DataCacheStatusService statusService;

    public DataCacheReadinessIndicator(DataCacheProperties properties, DataCacheStatusService statusService) {
        this.properties = properties;
        this.statusService = statusService;
    }

    @Override
    public Health health() {
        if (properties.getStartup().getExecutionMode() != StartupExecutionMode.BLOCK_UNTIL_REQUIRED_CACHE_READY) {
            return Health.up().withDetail("reason", "execution-mode is ASYNC; readiness is not gated on dataset load state").build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean allRequiredAvailable = true;
        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            String datasetName = entry.getKey();
            DatasetProperties config = entry.getValue();
            if (!config.isEnabled() || !config.isRequired()) {
                continue;
            }
            DatasetStatus status = statusService.getStatus(datasetName);
            DatasetAvailability availability = status.availability();
            details.put(datasetName, availability);
            if (availability != DatasetAvailability.AVAILABLE) {
                allRequiredAvailable = false;
            }
        }

        Health.Builder builder = allRequiredAvailable ? Health.up() : Health.down();
        return builder.withDetails(details).build();
    }
}

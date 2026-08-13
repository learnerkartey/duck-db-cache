package com.enterprise.datacache.feature.cache.health;

import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports whether the DuckDB cache itself is queryable - independent of whether Dremio (the
 * refresh source) is currently reachable. A dataset whose most recent refresh failed but which
 * still has an older ACTIVE version keeps serving queries, so this indicator stays UP; only the
 * separate {@code dremioSource} indicator reflects source connectivity problems.
 */
public class DataCacheHealthIndicator implements HealthIndicator {

    private final DataCacheStatusService statusService;

    public DataCacheHealthIndicator(DataCacheStatusService statusService) {
        this.statusService = statusService;
    }

    @Override
    public Health health() {
        try {
            Map<String, DatasetStatus> statuses = statusService.getAllStatuses();
            Health.Builder builder = Health.up();
            for (Map.Entry<String, DatasetStatus> entry : statuses.entrySet()) {
                DatasetStatus status = entry.getValue();
                builder.withDetail(entry.getKey(), Map.of(
                        "activeVersion", status.activeVersion() == null ? "NONE" : status.activeVersion(),
                        "activeRowCount", status.activeRowCount() == null ? 0 : status.activeRowCount(),
                        "lastRefreshStatus", status.lastRefreshStatus()));
            }
            return builder.build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}

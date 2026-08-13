package com.enterprise.datacache.feature.cache.health;

import com.enterprise.datacache.feature.cache.spi.DremioSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports Dremio (refresh source) connectivity, kept deliberately separate from
 * {@link DataCacheHealthIndicator} - a temporarily unreachable Dremio should surface here without
 * marking the whole application, or query serving from the existing cache, unavailable.
 */
public class DremioSourceHealthIndicator implements HealthIndicator {

    private final DremioSource dremioSource;

    public DremioSourceHealthIndicator(DremioSource dremioSource) {
        this.dremioSource = dremioSource;
    }

    @Override
    public Health health() {
        try {
            return dremioSource.isHealthy() ? Health.up().build()
                    : Health.down().withDetail("reason", "Dremio connectivity check failed").build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}

package com.enterprise.datacache.feature.cache.config;

/**
 * Controls the optional REST surface ({@code feature.cache.api}): {@code data-cache.api.*}. A host
 * application that only injects {@code DataCacheQueryService}/{@code DataCacheRefreshService}/
 * {@code DataCacheStatusService} directly and exposes its own endpoints can set
 * {@code data-cache.api.enabled=false} to skip registering these controllers entirely.
 */
public class ApiProperties {

    /** When false, no cache REST controllers (admin or query) are registered. Default true. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

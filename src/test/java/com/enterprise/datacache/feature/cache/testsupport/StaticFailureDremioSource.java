package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;

/** Test-only {@link DremioSource} that always throws the given exception when queried. */
public record StaticFailureDremioSource(RuntimeException toThrow) implements DremioSource {

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        throw toThrow;
    }

    @Override
    public boolean isHealthy() {
        return false;
    }

    @Override
    public void close() {
    }
}

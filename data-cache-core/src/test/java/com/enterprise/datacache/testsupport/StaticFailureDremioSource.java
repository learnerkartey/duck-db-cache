package com.enterprise.datacache.testsupport;

import com.enterprise.datacache.spi.ArrowBatchStream;
import com.enterprise.datacache.spi.DremioSource;

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

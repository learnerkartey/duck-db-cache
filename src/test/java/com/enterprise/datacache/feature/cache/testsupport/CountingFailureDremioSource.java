package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only {@link DremioSource} that always throws, but counts attempts so retry behavior can be asserted. */
public record CountingFailureDremioSource(RuntimeException toThrow, AtomicInteger attempts) implements DremioSource {

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        attempts.incrementAndGet();
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

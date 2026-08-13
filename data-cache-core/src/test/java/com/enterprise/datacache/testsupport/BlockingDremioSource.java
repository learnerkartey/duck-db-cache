package com.enterprise.datacache.testsupport;

import com.enterprise.datacache.exception.DremioSourceException;
import com.enterprise.datacache.spi.ArrowBatchStream;
import com.enterprise.datacache.spi.DremioSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only {@link DremioSource}: signals {@code started}, waits for {@code release}, then throws - used to hold a refresh "in flight". */
public record BlockingDremioSource(CountDownLatch started, CountDownLatch release) implements DremioSource {

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new DremioSourceException("released", false);
    }

    @Override
    public boolean isHealthy() {
        return false;
    }

    @Override
    public void close() {
    }
}

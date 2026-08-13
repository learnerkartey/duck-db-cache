package com.enterprise.datacache.feature.cache.refresh;

import java.time.Duration;

/** Injectable sleep strategy so retry-backoff tests run in milliseconds instead of real wall-clock delays. */
public interface RetrySleeper {

    void sleep(Duration duration) throws InterruptedException;

    RetrySleeper REAL = duration -> Thread.sleep(duration.toMillis());
}

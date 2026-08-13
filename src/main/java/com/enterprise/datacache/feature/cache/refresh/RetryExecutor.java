package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.RetryProperties;
import com.enterprise.datacache.feature.cache.exception.DataCacheException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a refresh attempt with exponential backoff, retrying only failures classified as
 * retryable ({@link DataCacheException#isRetryable()}). Configuration errors, invalid SQL,
 * unsupported types, and validation failures are never retried.
 */
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final RetrySleeper sleeper;

    public RetryExecutor(RetrySleeper sleeper) {
        this.sleeper = sleeper;
    }

    public <T> Outcome<T> execute(String datasetName, RetryProperties retry, Supplier<T> attempt) {
        Duration delay = retry.getInitialDelay();
        DataCacheException lastError = null;

        for (int attemptNumber = 1; attemptNumber <= retry.getMaxAttempts(); attemptNumber++) {
            try {
                T result = attempt.get();
                return new Outcome<>(result, attemptNumber, null);
            } catch (DataCacheException e) {
                lastError = e;
                boolean lastAttempt = attemptNumber == retry.getMaxAttempts();
                if (!e.isRetryable() || lastAttempt) {
                    log.warn("event=refresh-attempt-failed dataset={} attempt={} retryable={} terminal=true error={}",
                            datasetName, attemptNumber, e.isRetryable(), e.getMessage());
                    return new Outcome<>(null, attemptNumber, e);
                }
                log.warn("event=refresh-attempt-failed dataset={} attempt={} retryable=true willRetryAfterMs={} error={}",
                        datasetName, attemptNumber, delay.toMillis(), e.getMessage());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Outcome<>(null, attemptNumber, e);
                }
                delay = capDelay(Duration.ofMillis((long) (delay.toMillis() * retry.getMultiplier())), retry.getMaxDelay());
            }
        }
        return new Outcome<>(null, retry.getMaxAttempts(), lastError);
    }

    private Duration capDelay(Duration candidate, Duration max) {
        return candidate.compareTo(max) > 0 ? max : candidate;
    }

    public record Outcome<T>(T value, int attemptsMade, DataCacheException error) {
        public boolean isSuccess() {
            return error == null;
        }
    }
}

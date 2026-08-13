package com.enterprise.datacache.config;

import java.time.Duration;

/** Per-dataset exponential backoff retry policy applied to retryable refresh failures only. */
public class RetryProperties {

    private int maxAttempts = 3;

    private Duration initialDelay = Duration.ofSeconds(10);

    private double multiplier = 2.0;

    private Duration maxDelay = Duration.ofSeconds(60);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }
}

package com.enterprise.datacache.feature.cache.config;

import java.time.Duration;

/** Global startup execution settings: {@code data-cache.startup.*}. */
public class StartupProperties {

    private StartupExecutionMode executionMode = StartupExecutionMode.ASYNC;

    private Duration timeout = Duration.ofMinutes(30);

    public StartupExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(StartupExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}

package com.enterprise.datacache.feature.cache.config;

/** Root of {@code data-cache.logging.*}. Currently only controls refresh progress logging. */
public class LoggingProperties {

    private ProgressLoggingProperties progress = new ProgressLoggingProperties();

    public ProgressLoggingProperties getProgress() {
        return progress;
    }

    public void setProgress(ProgressLoggingProperties progress) {
        this.progress = progress;
    }
}

package com.enterprise.datacache.config;

/** Per-dataset startup configuration: {@code data-cache.datasets.<name>.startup.*}. */
public class DatasetStartupProperties {

    private StartupMode mode = StartupMode.USE_EXISTING_OR_CREATE;

    public StartupMode getMode() {
        return mode;
    }

    public void setMode(StartupMode mode) {
        this.mode = mode;
    }
}

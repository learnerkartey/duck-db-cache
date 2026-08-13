package com.enterprise.datacache.config;

/** Global refresh execution settings shared across all datasets. */
public class RefreshProperties {

    /** Bounded thread pool size - the maximum number of dataset refreshes that may run at once. */
    private int maxConcurrentDatasets = 2;

    /** How long a startup recovery pass waits for the metadata store to become available. */
    private boolean recoverOnStartup = true;

    public int getMaxConcurrentDatasets() {
        return maxConcurrentDatasets;
    }

    public void setMaxConcurrentDatasets(int maxConcurrentDatasets) {
        this.maxConcurrentDatasets = maxConcurrentDatasets;
    }

    public boolean isRecoverOnStartup() {
        return recoverOnStartup;
    }

    public void setRecoverOnStartup(boolean recoverOnStartup) {
        this.recoverOnStartup = recoverOnStartup;
    }
}

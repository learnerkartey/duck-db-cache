package com.enterprise.datacache.config;

import org.springframework.util.unit.DataSize;

/** File-backed DuckDB cache storage settings. Must point at a persistent volume in OpenShift. */
public class DuckDbProperties {

    /** Root directory; each dataset gets its own {@code <base-directory>/<dataset>/} subdirectory. */
    private String baseDirectory = "/data/cache";

    /** DuckDB temp/spill directory, shared across datasets. */
    private String tempDirectory = "/data/cache/temp";

    /** Per-connection DuckDB {@code memory_limit} pragma. */
    private DataSize memoryLimit = DataSize.ofGigabytes(8);

    /** Per-connection DuckDB {@code threads} pragma. */
    private int threads = 8;

    /** Number of completed (ACTIVE/PREVIOUS) versions retained per dataset. Must be >= 2 to allow zero-downtime cutover. */
    private int maxVersions = 2;

    public String getBaseDirectory() {
        return baseDirectory;
    }

    public void setBaseDirectory(String baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public String getTempDirectory() {
        return tempDirectory;
    }

    public void setTempDirectory(String tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    public DataSize getMemoryLimit() {
        return memoryLimit;
    }

    public void setMemoryLimit(DataSize memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getMaxVersions() {
        return maxVersions;
    }

    public void setMaxVersions(int maxVersions) {
        this.maxVersions = maxVersions;
    }
}

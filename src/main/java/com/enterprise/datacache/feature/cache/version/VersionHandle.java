package com.enterprise.datacache.feature.cache.version;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A caller's pinned reference to one specific dataset version, obtained via
 * {@link VersionManager#pinActive(String)}. While held, the referenced physical DuckDB file is
 * guaranteed not to be deleted even if a newer version becomes ACTIVE concurrently. Must be
 * closed exactly once - ideally via try-with-resources - to release the reference.
 */
public final class VersionHandle implements AutoCloseable {

    private final VersionManager owner;
    private final String datasetName;
    private final long version;
    private final Path filePath;
    private final String tableName;
    private final AtomicBoolean released = new AtomicBoolean(false);

    VersionHandle(VersionManager owner, String datasetName, long version, Path filePath, String tableName) {
        this.owner = owner;
        this.datasetName = datasetName;
        this.version = version;
        this.filePath = filePath;
        this.tableName = tableName;
    }

    public String datasetName() {
        return datasetName;
    }

    public long version() {
        return version;
    }

    public Path filePath() {
        return filePath;
    }

    public String tableName() {
        return tableName;
    }

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            owner.release(new VersionKey(datasetName, version));
        }
    }
}

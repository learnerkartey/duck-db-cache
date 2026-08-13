package com.enterprise.datacache.version;

import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.exception.DatasetNotFoundException;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.model.DatasetVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the ACTIVE/PREVIOUS lifecycle for every dataset's physical DuckDB files: atomic activation,
 * in-memory reader reference counting so a version in use is never deleted out from under a running
 * query, and safe cleanup of versions that fall outside the retained window once their last reader
 * releases them.
 *
 * <p>This provides only in-process coordination. Running multiple replicas that each perform
 * refreshes against the same shared storage requires an external distributed lock - see
 * docs/18-OPERATIONS.md and docs/20-OPENSHIFT-DEPLOYMENT.md for the single-writer-replica
 * assumption this class makes explicit.
 */
public class VersionManager {

    private static final Logger log = LoggerFactory.getLogger(VersionManager.class);

    private final MetadataStore metadataStore;
    private final DuckDbProperties duckDbProperties;
    private final ConcurrentHashMap<VersionKey, AtomicInteger> readerCounts = new ConcurrentHashMap<>();
    private final Set<VersionKey> pendingDeletion = ConcurrentHashMap.newKeySet();

    public VersionManager(MetadataStore metadataStore, DuckDbProperties duckDbProperties) {
        this.metadataStore = metadataStore;
        this.duckDbProperties = duckDbProperties;
    }

    /**
     * Pins the dataset's current ACTIVE version for the duration of one query, guaranteeing its
     * physical file survives until the returned handle is closed - even if a newer version becomes
     * ACTIVE in the meantime. This is the mechanism behind query version snapshotting.
     */
    public synchronized VersionHandle pinActive(String datasetName) {
        DatasetVersion active = metadataStore.findActive(datasetName)
                .orElseThrow(() -> new DatasetNotFoundException(datasetName + "' has no ACTIVE version yet (never refreshed successfully)"));
        VersionKey key = new VersionKey(datasetName, active.version());
        readerCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        return new VersionHandle(this, datasetName, active.version(), active.filePath(), active.tableName());
    }

    synchronized void release(VersionKey key) {
        AtomicInteger count = readerCounts.get(key);
        if (count == null) {
            return;
        }
        if (count.decrementAndGet() <= 0) {
            readerCounts.remove(key);
            if (pendingDeletion.remove(key)) {
                metadataStore.findVersion(key.datasetName(), key.version())
                        .ifPresent(v -> physicallyDelete(key.datasetName(), key.version(), v.filePath()));
            }
        }
    }

    /** Atomically promotes {@code newVersion} to ACTIVE, demotes the prior ACTIVE to PREVIOUS, then
     * cleans up (or defers cleanup of) any versions that now fall outside the retained window. */
    public synchronized void activate(String datasetName, long newVersion) {
        metadataStore.activateVersion(datasetName, newVersion);
        log.info("event=version-activated dataset={} version={}", datasetName, newVersion);
        cleanupObsolete(datasetName);
    }

    private void cleanupObsolete(String datasetName) {
        List<DatasetVersion> previous = metadataStore.findPrevious(datasetName);
        int keep = Math.max(0, duckDbProperties.getMaxVersions() - 1);
        for (int i = keep; i < previous.size(); i++) {
            DatasetVersion obsolete = previous.get(i);
            deleteOrDefer(datasetName, obsolete.version(), obsolete.filePath());
        }
    }

    private void deleteOrDefer(String datasetName, long version, Path filePath) {
        VersionKey key = new VersionKey(datasetName, version);
        AtomicInteger count = readerCounts.get(key);
        if (count != null && count.get() > 0) {
            pendingDeletion.add(key);
            log.info("event=version-cleanup-deferred dataset={} version={} activeReaders={}", datasetName, version,
                    count.get());
            return;
        }
        physicallyDelete(datasetName, version, filePath);
    }

    private void physicallyDelete(String datasetName, long version, Path filePath) {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("event=version-file-delete-failed dataset={} version={} path={}", datasetName, version,
                    filePath, e);
        }
        metadataStore.deleteVersionRecord(datasetName, version);
        log.info("event=version-cleanup-completed dataset={} version={}", datasetName, version);
    }

    /** Re-applies the retention/cleanup pass without activating a new version - used by startup recovery after reconciling stray state. */
    public synchronized void reconcileRetention(String datasetName) {
        cleanupObsolete(datasetName);
    }

    /** Number of in-flight query readers currently holding a reference to {@code datasetName}/{@code version}. Test/ops visibility only. */
    public int activeReaderCount(String datasetName, long version) {
        AtomicInteger count = readerCounts.get(new VersionKey(datasetName, version));
        return count == null ? 0 : count.get();
    }

    public Optional<DatasetVersion> findActive(String datasetName) {
        return metadataStore.findActive(datasetName);
    }
}

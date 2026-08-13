package com.enterprise.datacache.refresh;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.VersionState;
import com.enterprise.datacache.version.VersionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles persisted metadata against physical storage on application/pod restart. Deterministic
 * rules:
 * <ul>
 *   <li>Any version left in BUILDING or VALIDATING is a crash artifact - it never had a chance to
 *       be validated or activated, so it is marked FAILED and its physical file is discarded.</li>
 *   <li>If the ACTIVE version's physical file is missing, the version is marked FAILED (the
 *       dataset then reports "no active version" until the next successful refresh) rather than
 *       silently serving nothing or crashing the query engine.</li>
 *   <li>A PREVIOUS version whose file is missing is simply forgotten - no in-flight readers can
 *       exist immediately after process start.</li>
 *   <li>Any {@code *.duckdb} file on disk with no matching metadata row (an orphan from a build
 *       that crashed before its metadata row was even inserted, or from external tampering) is
 *       deleted.</li>
 * </ul>
 * Runs once, synchronously, before the application is considered ready to serve refreshes.
 */
public class StartupRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryService.class);

    private final DataCacheProperties properties;
    private final MetadataStore metadataStore;
    private final VersionManager versionManager;

    public StartupRecoveryService(DataCacheProperties properties, MetadataStore metadataStore,
            VersionManager versionManager) {
        this.properties = properties;
        this.metadataStore = metadataStore;
        this.versionManager = versionManager;
    }

    public void recoverAll() {
        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            if (!entry.getValue().isEnabled()) {
                continue;
            }
            recoverDataset(entry.getKey());
        }
    }

    private void recoverDataset(String datasetName) {
        for (VersionState interrupted : new VersionState[] {VersionState.BUILDING, VersionState.VALIDATING}) {
            for (DatasetVersion version : metadataStore.findByState(datasetName, interrupted)) {
                log.warn("event=startup-recovery-interrupted-build dataset={} version={} previousState={}",
                        datasetName, version.version(), interrupted);
                metadataStore.recordFailure(datasetName, version.version(), "STARTUP_RECOVERY",
                        "Interrupted " + interrupted + " detected on startup; discarded");
                deleteQuietly(version.filePath());
            }
        }

        metadataStore.findActive(datasetName).ifPresent(active -> {
            if (!Files.exists(active.filePath())) {
                log.error("event=startup-recovery-active-file-missing dataset={} version={} path={}",
                        datasetName, active.version(), active.filePath());
                metadataStore.recordFailure(datasetName, active.version(), "MISSING_FILE",
                        "ACTIVE version's physical file was missing at startup");
            }
        });

        for (DatasetVersion previous : metadataStore.findPrevious(datasetName)) {
            if (!Files.exists(previous.filePath())) {
                log.warn("event=startup-recovery-previous-file-missing dataset={} version={} path={}",
                        datasetName, previous.version(), previous.filePath());
                metadataStore.deleteVersionRecord(datasetName, previous.version());
            }
        }

        cleanOrphanFiles(datasetName);
        versionManager.reconcileRetention(datasetName);
    }

    private void cleanOrphanFiles(String datasetName) {
        Path dir = RefreshCoordinator.datasetDirectory(properties.getDuckdb(), datasetName);
        if (!Files.isDirectory(dir)) {
            return;
        }
        Set<Path> known = metadataStore.findAll(datasetName).stream()
                .map(v -> v.filePath().toAbsolutePath())
                .collect(Collectors.toSet());
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".duckdb"))
                    .filter(p -> !known.contains(p.toAbsolutePath()))
                    .forEach(p -> {
                        log.warn("event=startup-recovery-orphan-file-removed dataset={} path={}", datasetName, p);
                        deleteQuietly(p);
                    });
        } catch (IOException e) {
            log.warn("event=startup-recovery-orphan-scan-failed dataset={} dir={}", datasetName, dir, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("event=startup-recovery-file-delete-failed path={}", path, e);
        }
    }
}

package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.model.DatasetVersion;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshManifestStatus;
import com.enterprise.datacache.feature.cache.model.VersionState;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles persisted metadata against physical storage on application/pod restart. Deterministic
 * rules:
 * <ul>
 *   <li>A version left in BUILDING is normally a crash artifact - it never had a chance to be
 *       validated or activated. If resume is disabled (globally or for this dataset), or no
 *       resumable {@link RefreshManifest} matches it, or the manifest is expired/incompatible/the
 *       file cannot be opened, it is marked FAILED and its physical file is discarded, exactly as
 *       before this feature existed. But a version whose manifest shows it IS safely resumable
 *       (resume enabled, a live BUILDING manifest, the physical file opens cleanly, and the build is
 *       within {@code data-cache.resume.max-resume-age}) is deliberately left untouched - its
 *       {@code dataset_versions} state stays BUILDING and its manifest stays BUILDING - so the next
 *       refresh attempt (triggered by {@code DataCacheStartupCoordinator} or a later schedule/manual
 *       call) resumes it from its last completed chunk instead of restarting from zero. See
 *       docs/RESUMABLE-REFRESH-AND-RECOVERY.md.</li>
 *   <li>A version left in VALIDATING already finished its data load and crashed only during the
 *       (cheap, Dremio-free) validation/activation step; it is always treated as a crash artifact,
 *       the same as before this feature existed - re-running the full load is safe and simple, and
 *       the validation window is short enough that resuming it specifically is not worth the added
 *       complexity.</li>
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
            recoverDataset(entry.getKey(), entry.getValue());
        }
    }

    private void recoverDataset(String datasetName, DatasetProperties config) {
        for (DatasetVersion version : metadataStore.findByState(datasetName, VersionState.BUILDING)) {
            if (isSafelyResumable(datasetName, config, version)) {
                continue;
            }
            discardInterruptedBuild(datasetName, version, VersionState.BUILDING);
        }
        for (DatasetVersion version : metadataStore.findByState(datasetName, VersionState.VALIDATING)) {
            discardInterruptedBuild(datasetName, version, VersionState.VALIDATING);
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

    private void discardInterruptedBuild(String datasetName, DatasetVersion version, VersionState interrupted) {
        log.warn("event=startup-recovery-interrupted-build dataset={} version={} previousState={}",
                datasetName, version.version(), interrupted);
        metadataStore.recordFailure(datasetName, version.version(), "STARTUP_RECOVERY",
                "Interrupted " + interrupted + " detected on startup; discarded");
        if (interrupted == VersionState.BUILDING) {
            // A leftover BUILDING manifest for this version - e.g. resume was disabled after it was
            // created, or it was just marked expired/corrupt above - must not linger as a live
            // BUILDING candidate once its physical file is deleted below, or a later resume attempt
            // (e.g. after resume is re-enabled) could target a file that no longer exists.
            metadataStore.findManifest(datasetName, version.version())
                    .filter(m -> m.status() == RefreshManifestStatus.BUILDING)
                    .ifPresent(m -> metadataStore.updateManifestStatus(datasetName, version.version(),
                            RefreshManifestStatus.ABANDONED));
        }
        deleteQuietly(version.filePath());
    }

    /**
     * Decides whether a BUILDING version found at startup is a genuinely resumable, safely
     * preservable partial cache rather than an ordinary crash artifact. Every check here is
     * deliberately cheap (no Dremio call, no chunk-level metadata scan) - the expensive source SQL
     * hash / schema-hash / snapshot compatibility checks are re-verified by
     * {@code ResumableRefreshExecutor} at actual resume time, which is the single source of truth
     * for whether a resume can proceed. This method only decides whether the file is even worth
     * keeping around until that point.
     */
    private boolean isSafelyResumable(String datasetName, DatasetProperties config, DatasetVersion version) {
        if (!properties.getResume().isEnabled()) {
            return false;
        }
        DatasetResumeProperties resumeConfig = config.getResume();
        if (!resumeConfig.isEnabled()) {
            return false;
        }
        Optional<RefreshManifest> manifestOpt = metadataStore.findManifest(datasetName, version.version());
        if (manifestOpt.isEmpty()) {
            log.warn("event=startup-recovery-resume-manifest-missing dataset={} version={}",
                    datasetName, version.version());
            return false;
        }
        RefreshManifest manifest = manifestOpt.get();
        if (manifest.status() != RefreshManifestStatus.BUILDING || !manifest.resumeEnabled()) {
            return false;
        }

        Duration age = Duration.between(manifest.startedAt(), Instant.now());
        if (age.compareTo(properties.getResume().getMaxResumeAge()) > 0) {
            log.warn("event=startup-recovery-resume-expired dataset={} version={} age={} maxResumeAge={}",
                    datasetName, version.version(), age, properties.getResume().getMaxResumeAge());
            metadataStore.updateManifestStatus(datasetName, version.version(), RefreshManifestStatus.ABANDONED);
            return false;
        }

        if (!Files.exists(version.filePath()) || !isOpenable(version.filePath())) {
            log.warn("event=startup-recovery-resume-file-corrupt-or-missing dataset={} version={} path={}",
                    datasetName, version.version(), version.filePath());
            metadataStore.updateManifestStatus(datasetName, version.version(), RefreshManifestStatus.FAILED);
            return false;
        }

        log.info("event=startup-recovery-resumable-build-preserved dataset={} version={} completedChunks={} "
                        + "totalChunks={} rowsCommitted={} refreshId={}",
                datasetName, version.version(), manifest.completedChunks(), manifest.totalChunks(),
                manifest.rowsCommitted(), manifest.refreshId());
        return true;
    }

    private boolean isOpenable(Path path) {
        try (DuckDBConnection connection = DuckDbConnectionFactory.openReadOnly(path);
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (RuntimeException | SQLException e) {
            return false;
        }
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

package com.enterprise.datacache.feature.cache.metadata;

import com.enterprise.datacache.feature.cache.exception.DuckDbWriteException;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.model.ChunkStatus;
import com.enterprise.datacache.feature.cache.model.ConsistencyMode;
import com.enterprise.datacache.feature.cache.model.DatasetChunk;
import com.enterprise.datacache.feature.cache.model.DatasetVersion;
import com.enterprise.datacache.feature.cache.model.RefreshManifest;
import com.enterprise.datacache.feature.cache.model.RefreshManifestStatus;
import com.enterprise.datacache.feature.cache.model.ResumeStrategyType;
import com.enterprise.datacache.feature.cache.model.ValidationStatus;
import com.enterprise.datacache.feature.cache.model.VersionState;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent metadata store for dataset version lifecycle state, backed by a single small
 * file-backed DuckDB database ({@code <base-directory>/metadata/cache_metadata.duckdb}). One
 * connection is held open for the process lifetime and all access is serialized through this
 * instance, since JDBC connections/statements are not thread-safe and metadata operations are
 * low-frequency compared to query traffic.
 */
public class MetadataStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetadataStore.class);

    private final DuckDBConnection connection;

    public MetadataStore(DuckDBConnection connection) {
        this.connection = connection;
        createSchema();
    }

    public static Path defaultMetadataFile(String baseDirectory) {
        return Path.of(baseDirectory, "metadata", "cache_metadata.duckdb");
    }

    private void createSchema() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS dataset_versions (
                    dataset_name VARCHAR NOT NULL,
                    version BIGINT NOT NULL,
                    state VARCHAR NOT NULL,
                    file_path VARCHAR NOT NULL,
                    table_name VARCHAR NOT NULL,
                    row_count BIGINT NOT NULL DEFAULT 0,
                    bytes_loaded BIGINT NOT NULL DEFAULT 0,
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    duration_ms BIGINT,
                    source_sql_hash VARCHAR,
                    validation_status VARCHAR NOT NULL DEFAULT 'NOT_RUN',
                    error_code VARCHAR,
                    error_summary VARCHAR,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (dataset_name, version)
                )
                """;
        String manifestDdl = """
                CREATE TABLE IF NOT EXISTS dataset_refresh_manifest (
                    dataset_name VARCHAR NOT NULL,
                    version BIGINT NOT NULL,
                    refresh_id VARCHAR NOT NULL,
                    source_sql_hash VARCHAR NOT NULL,
                    source_schema_hash VARCHAR,
                    resume_enabled BOOLEAN NOT NULL,
                    resume_strategy VARCHAR,
                    partition_column VARCHAR,
                    chunk_size BIGINT,
                    source_snapshot_id VARCHAR,
                    consistency_mode VARCHAR,
                    total_chunks BIGINT,
                    completed_chunks BIGINT NOT NULL DEFAULT 0,
                    failed_chunks BIGINT NOT NULL DEFAULT 0,
                    rows_committed BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR NOT NULL,
                    started_at TIMESTAMP NOT NULL,
                    last_updated_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (dataset_name, version)
                )
                """;
        String chunkDdl = """
                CREATE TABLE IF NOT EXISTS dataset_refresh_chunk (
                    dataset_name VARCHAR NOT NULL,
                    version BIGINT NOT NULL,
                    chunk_id BIGINT NOT NULL,
                    partition_start VARCHAR,
                    partition_end VARCHAR,
                    status VARCHAR NOT NULL,
                    rows_loaded BIGINT NOT NULL DEFAULT 0,
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    attempt_count INT NOT NULL DEFAULT 0,
                    error_code VARCHAR,
                    error_summary VARCHAR,
                    PRIMARY KEY (dataset_name, version, chunk_id)
                )
                """;
        synchronized (this) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl);
                statement.execute(manifestDdl);
                statement.execute(chunkDdl);
            } catch (SQLException e) {
                throw new DuckDbWriteException("Failed to create metadata schema", e);
            }
        }
    }

    public synchronized long nextVersionNumber(String datasetName) {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM dataset_versions WHERE dataset_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, datasetName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to compute next version for dataset " + datasetName, e);
        }
    }

    public synchronized void createBuildingVersion(String datasetName, long version, Path filePath, String tableName,
            String sourceSqlHash) {
        Instant now = Instant.now();
        String sql = """
                INSERT INTO dataset_versions
                    (dataset_name, version, state, file_path, table_name, source_sql_hash, started_at, created_at, updated_at)
                VALUES (?, ?, 'BUILDING', ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, datasetName);
            ps.setLong(2, version);
            ps.setString(3, filePath.toString());
            ps.setString(4, tableName);
            ps.setString(5, sourceSqlHash);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to record BUILDING version " + datasetName + " v" + version, e);
        }
    }

    public synchronized void recordLoadStats(String datasetName, long version, long rowCount, long bytesLoaded) {
        execUpdate("UPDATE dataset_versions SET row_count = ?, bytes_loaded = ?, updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setLong(1, rowCount);
                    ps.setLong(2, bytesLoaded);
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.setString(4, datasetName);
                    ps.setLong(5, version);
                });
    }

    public synchronized void updateState(String datasetName, long version, VersionState state) {
        execUpdate("UPDATE dataset_versions SET state = ?, updated_at = ? WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setString(1, state.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, datasetName);
                    ps.setLong(4, version);
                });
    }

    public synchronized void recordValidation(String datasetName, long version, ValidationStatus status) {
        execUpdate("UPDATE dataset_versions SET validation_status = ?, updated_at = ? WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setString(1, status.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, datasetName);
                    ps.setLong(4, version);
                });
    }

    public synchronized void recordFailure(String datasetName, long version, String errorCode, String errorSummary) {
        Instant now = Instant.now();
        execUpdate("UPDATE dataset_versions SET state = 'FAILED', error_code = ?, error_summary = ?, "
                + "completed_at = ?, duration_ms = CAST(epoch_ms(?) - epoch_ms(started_at) AS BIGINT), updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setString(1, errorCode);
                    ps.setString(2, truncate(errorSummary, 2000));
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setTimestamp(4, Timestamp.from(now));
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.setString(6, datasetName);
                    ps.setLong(7, version);
                });
    }

    public synchronized void recordCompletion(String datasetName, long version, long durationMs) {
        Instant now = Instant.now();
        execUpdate("UPDATE dataset_versions SET completed_at = ?, duration_ms = ?, updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setLong(2, durationMs);
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setString(4, datasetName);
                    ps.setLong(5, version);
                });
    }

    /**
     * Atomically (in one DuckDB transaction) promotes {@code newVersion} to ACTIVE and, if a
     * different version is currently ACTIVE, demotes it to PREVIOUS. Never touches any version's
     * physical file - callers own file lifecycle and reader-reference-counted deletion separately.
     */
    public synchronized void activateVersion(String datasetName, long newVersion) {
        try {
            connection.setAutoCommit(false);
            Instant now = Instant.now();
            try (PreparedStatement demote = connection.prepareStatement(
                    "UPDATE dataset_versions SET state = 'PREVIOUS', updated_at = ? "
                            + "WHERE dataset_name = ? AND state = 'ACTIVE' AND version <> ?")) {
                demote.setTimestamp(1, Timestamp.from(now));
                demote.setString(2, datasetName);
                demote.setLong(3, newVersion);
                demote.executeUpdate();
            }
            try (PreparedStatement activate = connection.prepareStatement(
                    "UPDATE dataset_versions SET state = 'ACTIVE', updated_at = ? WHERE dataset_name = ? AND version = ?")) {
                activate.setTimestamp(1, Timestamp.from(now));
                activate.setString(2, datasetName);
                activate.setLong(3, newVersion);
                int updated = activate.executeUpdate();
                if (updated != 1) {
                    throw new DuckDbWriteException("Cannot activate unknown version " + datasetName + " v" + newVersion);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DuckDbWriteException("Failed to atomically activate " + datasetName + " v" + newVersion, e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    public synchronized void updateFilePath(String datasetName, long version, Path newFilePath) {
        execUpdate("UPDATE dataset_versions SET file_path = ?, updated_at = ? WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setString(1, newFilePath.toString());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, datasetName);
                    ps.setLong(4, version);
                });
    }

    public synchronized void deleteVersionRecord(String datasetName, long version) {
        execUpdate("DELETE FROM dataset_versions WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setString(1, datasetName);
            ps.setLong(2, version);
        });
    }

    public synchronized Optional<DatasetVersion> findVersion(String datasetName, long version) {
        return queryOne("SELECT * FROM dataset_versions WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setString(1, datasetName);
            ps.setLong(2, version);
        });
    }

    public synchronized Optional<DatasetVersion> findActive(String datasetName) {
        return queryOne("SELECT * FROM dataset_versions WHERE dataset_name = ? AND state = 'ACTIVE' "
                + "ORDER BY version DESC LIMIT 1", ps -> ps.setString(1, datasetName));
    }

    public synchronized List<DatasetVersion> findPrevious(String datasetName) {
        return queryList("SELECT * FROM dataset_versions WHERE dataset_name = ? AND state = 'PREVIOUS' "
                + "ORDER BY version DESC", ps -> ps.setString(1, datasetName));
    }

    public synchronized List<DatasetVersion> findAll(String datasetName) {
        return queryList("SELECT * FROM dataset_versions WHERE dataset_name = ? ORDER BY version DESC",
                ps -> ps.setString(1, datasetName));
    }

    public synchronized List<DatasetVersion> findByState(String datasetName, VersionState state) {
        return queryList("SELECT * FROM dataset_versions WHERE dataset_name = ? AND state = ? ORDER BY version DESC",
                ps -> {
                    ps.setString(1, datasetName);
                    ps.setString(2, state.name());
                });
    }

    public synchronized List<String> distinctDatasetNames() {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT DISTINCT dataset_name FROM dataset_versions")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to list dataset names from metadata", e);
        }
        return names;
    }

    // --- Resumable refresh: manifest -----------------------------------------------------------

    public synchronized void createManifest(RefreshManifest manifest) {
        String sql = """
                INSERT INTO dataset_refresh_manifest
                    (dataset_name, version, refresh_id, source_sql_hash, source_schema_hash, resume_enabled,
                     resume_strategy, partition_column, chunk_size, source_snapshot_id, consistency_mode,
                     total_chunks, completed_chunks, failed_chunks, rows_committed, status, started_at, last_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, manifest.datasetName());
            ps.setLong(2, manifest.version());
            ps.setString(3, manifest.refreshId());
            ps.setString(4, manifest.sourceSqlHash());
            ps.setString(5, manifest.sourceSchemaHash());
            ps.setBoolean(6, manifest.resumeEnabled());
            ps.setString(7, manifest.resumeStrategy() == null ? null : manifest.resumeStrategy().name());
            ps.setString(8, manifest.partitionColumn());
            if (manifest.chunkSize() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, manifest.chunkSize());
            }
            ps.setString(10, manifest.sourceSnapshotId());
            ps.setString(11, manifest.consistencyMode() == null ? null : manifest.consistencyMode().name());
            if (manifest.totalChunks() == null) {
                ps.setNull(12, java.sql.Types.BIGINT);
            } else {
                ps.setLong(12, manifest.totalChunks());
            }
            ps.setString(13, manifest.status().name());
            ps.setTimestamp(14, Timestamp.from(manifest.startedAt()));
            ps.setTimestamp(15, Timestamp.from(manifest.lastUpdatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to create refresh manifest " + manifest.datasetName()
                    + " v" + manifest.version(), e);
        }
    }

    public synchronized void updateManifestTotalChunks(String datasetName, long version, long totalChunks) {
        execUpdate("UPDATE dataset_refresh_manifest SET total_chunks = ?, last_updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setLong(1, totalChunks);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, datasetName);
            ps.setLong(4, version);
        });
    }

    public synchronized void updateManifestSchemaHash(String datasetName, long version, String schemaHash) {
        execUpdate("UPDATE dataset_refresh_manifest SET source_schema_hash = ?, last_updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setString(1, schemaHash);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, datasetName);
            ps.setLong(4, version);
        });
    }

    public synchronized void recordManifestChunkProgress(String datasetName, long version, long completedChunks,
            long failedChunks, long rowsCommitted) {
        execUpdate("UPDATE dataset_refresh_manifest SET completed_chunks = ?, failed_chunks = ?, rows_committed = ?, "
                + "last_updated_at = ? WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setLong(1, completedChunks);
            ps.setLong(2, failedChunks);
            ps.setLong(3, rowsCommitted);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, datasetName);
            ps.setLong(6, version);
        });
    }

    public synchronized void updateManifestStatus(String datasetName, long version, RefreshManifestStatus status) {
        execUpdate("UPDATE dataset_refresh_manifest SET status = ?, last_updated_at = ? "
                + "WHERE dataset_name = ? AND version = ?", ps -> {
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, datasetName);
            ps.setLong(4, version);
        });
    }

    public synchronized Optional<RefreshManifest> findManifest(String datasetName, long version) {
        return queryOneManifest("SELECT * FROM dataset_refresh_manifest WHERE dataset_name = ? AND version = ?",
                ps -> {
                    ps.setString(1, datasetName);
                    ps.setLong(2, version);
                });
    }

    /** The most recent manifest still in BUILDING status - the only candidate for resume. */
    public synchronized Optional<RefreshManifest> findBuildingManifest(String datasetName) {
        return queryOneManifest("SELECT * FROM dataset_refresh_manifest WHERE dataset_name = ? AND status = 'BUILDING' "
                + "ORDER BY version DESC LIMIT 1", ps -> ps.setString(1, datasetName));
    }

    private Optional<RefreshManifest> queryOneManifest(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapManifestRow(rs));
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Manifest query failed: " + sql, e);
        }
    }

    private RefreshManifest mapManifestRow(ResultSet rs) throws SQLException {
        long totalChunks = rs.getLong("total_chunks");
        boolean totalChunksNull = rs.wasNull();
        long chunkSize = rs.getLong("chunk_size");
        boolean chunkSizeNull = rs.wasNull();
        String strategy = rs.getString("resume_strategy");
        String consistency = rs.getString("consistency_mode");
        return new RefreshManifest(
                rs.getString("dataset_name"),
                rs.getLong("version"),
                rs.getString("refresh_id"),
                rs.getString("source_sql_hash"),
                rs.getString("source_schema_hash"),
                rs.getBoolean("resume_enabled"),
                strategy == null ? null : ResumeStrategyType.valueOf(strategy),
                rs.getString("partition_column"),
                chunkSizeNull ? null : chunkSize,
                rs.getString("source_snapshot_id"),
                consistency == null ? null : ConsistencyMode.valueOf(consistency),
                totalChunksNull ? null : totalChunks,
                rs.getLong("completed_chunks"),
                rs.getLong("failed_chunks"),
                rs.getLong("rows_committed"),
                RefreshManifestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("last_updated_at").toInstant());
    }

    // --- Resumable refresh: chunks -------------------------------------------------------------

    /** Bulk-inserts every planned chunk as PENDING, in one transaction. */
    public synchronized void planChunks(String datasetName, long version, List<ChunkDefinition> chunks) {
        String sql = "INSERT INTO dataset_refresh_chunk "
                + "(dataset_name, version, chunk_id, partition_start, partition_end, status, rows_loaded, attempt_count) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 0)";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (ChunkDefinition chunk : chunks) {
                    ps.setString(1, datasetName);
                    ps.setLong(2, version);
                    ps.setLong(3, chunk.chunkId());
                    ps.setString(4, chunk.partitionStart());
                    ps.setString(5, chunk.partitionEnd());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DuckDbWriteException("Failed to plan " + chunks.size() + " chunks for " + datasetName
                    + " v" + version, e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    public synchronized List<DatasetChunk> findChunks(String datasetName, long version) {
        List<DatasetChunk> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM dataset_refresh_chunk WHERE dataset_name = ? AND version = ? ORDER BY chunk_id")) {
            ps.setString(1, datasetName);
            ps.setLong(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapChunkRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to load chunks for " + datasetName + " v" + version, e);
        }
        return results;
    }

    /** Any chunk found RUNNING is a crash artifact - at most one process ever loads a dataset's
     *  chunks at a time (enforced by {@code RefreshLock}), so a RUNNING row can only mean the
     *  process that started it never got to mark it COMPLETED or FAILED. */
    public synchronized int resetStaleRunningChunksToPending(String datasetName, long version) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE dataset_refresh_chunk SET status = 'PENDING' "
                        + "WHERE dataset_name = ? AND version = ? AND status = 'RUNNING'")) {
            ps.setString(1, datasetName);
            ps.setLong(2, version);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to reset stale RUNNING chunks for " + datasetName + " v" + version, e);
        }
    }

    public synchronized void markChunkRunning(String datasetName, long version, long chunkId) {
        execUpdate("UPDATE dataset_refresh_chunk SET status = 'RUNNING', started_at = ?, attempt_count = attempt_count + 1 "
                + "WHERE dataset_name = ? AND version = ? AND chunk_id = ?", ps -> {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, datasetName);
            ps.setLong(3, version);
            ps.setLong(4, chunkId);
        });
    }

    public synchronized void markChunkCompleted(String datasetName, long version, long chunkId, long rowsLoaded) {
        execUpdate("UPDATE dataset_refresh_chunk SET status = 'COMPLETED', completed_at = ?, rows_loaded = ?, "
                + "error_code = NULL, error_summary = NULL "
                + "WHERE dataset_name = ? AND version = ? AND chunk_id = ?", ps -> {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, rowsLoaded);
            ps.setString(3, datasetName);
            ps.setLong(4, version);
            ps.setLong(5, chunkId);
        });
    }

    public synchronized void markChunkFailed(String datasetName, long version, long chunkId, String errorCode,
            String errorSummary) {
        execUpdate("UPDATE dataset_refresh_chunk SET status = 'FAILED', error_code = ?, error_summary = ? "
                + "WHERE dataset_name = ? AND version = ? AND chunk_id = ?", ps -> {
            ps.setString(1, errorCode);
            ps.setString(2, truncate(errorSummary, 2000));
            ps.setString(3, datasetName);
            ps.setLong(4, version);
            ps.setLong(5, chunkId);
        });
    }

    private DatasetChunk mapChunkRow(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new DatasetChunk(
                rs.getString("dataset_name"),
                rs.getLong("version"),
                rs.getLong("chunk_id"),
                rs.getString("partition_start"),
                rs.getString("partition_end"),
                ChunkStatus.valueOf(rs.getString("status")),
                rs.getLong("rows_loaded"),
                startedAt == null ? null : startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                rs.getInt("attempt_count"),
                rs.getString("error_code"),
                rs.getString("error_summary"));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void execUpdate(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Metadata update failed: " + sql, e);
        }
    }

    private Optional<DatasetVersion> queryOne(String sql, Binder binder) {
        List<DatasetVersion> results = queryList(sql, binder);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private List<DatasetVersion> queryList(String sql, Binder binder) {
        List<DatasetVersion> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Metadata query failed: " + sql, e);
        }
        return results;
    }

    private DatasetVersion mapRow(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        long durationMs = rs.getLong("duration_ms");
        return new DatasetVersion(
                rs.getString("dataset_name"),
                rs.getLong("version"),
                VersionState.valueOf(rs.getString("state")),
                Path.of(rs.getString("file_path")),
                rs.getString("table_name"),
                rs.getLong("row_count"),
                rs.getLong("bytes_loaded"),
                startedAt == null ? null : startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                rs.wasNull() ? null : durationMs,
                rs.getString("source_sql_hash"),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("event=metadata-rollback-failed", e);
        }
    }

    private void setAutoCommitQuietly(boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException e) {
            log.warn("event=metadata-autocommit-reset-failed", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("event=metadata-store-close-failed", e);
        }
    }
}

package com.enterprise.datacache.metadata;

import com.enterprise.datacache.exception.DuckDbWriteException;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.ValidationStatus;
import com.enterprise.datacache.model.VersionState;
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
        synchronized (this) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl);
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

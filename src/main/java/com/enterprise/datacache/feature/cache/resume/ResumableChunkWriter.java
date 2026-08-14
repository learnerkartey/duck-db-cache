package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.arrow.ArrowToDuckDbTypeMapper;
import com.enterprise.datacache.feature.cache.arrow.ColumnMapping;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.exception.DuckDbWriteException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent, per-chunk writer for a resumable BUILDING DuckDB file. Every chunk (including
 * retries) is loaded as {@code DELETE stale rows for this chunk, then re-append, then COMMIT} in
 * one DuckDB transaction, using an internal {@code __cache_chunk_id} column to identify which rows
 * belong to which chunk - so re-running chunk N after a crash (whether the previous attempt never
 * committed, or committed but crashed before its completion metadata was persisted) always leaves
 * the table with exactly one copy of chunk N's rows, never duplicates. See
 * docs/RESUMABLE-REFRESH-AND-RECOVERY.md#idempotent-chunk-design.
 *
 * <p>{@link #finalizeTable()} drops the internal column once every chunk has completed, so the
 * business table exposed to validation/queries never carries it.
 */
public class ResumableChunkWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResumableChunkWriter.class);
    static final String CHUNK_ID_COLUMN = "__cache_chunk_id";

    private final String tableName;
    private final DuckDBConnection connection;
    private List<ColumnMapping> columnMappings;

    private DuckDBAppender appender;
    private long currentChunkId = -1;
    private long currentChunkRows;
    private long currentChunkBytes;

    public ResumableChunkWriter(Path filePath, String tableName, DuckDbProperties properties) {
        this.tableName = tableName;
        this.connection = DuckDbConnectionFactory.open(filePath, properties);
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to disable autocommit for resumable writer on table " + tableName, e);
        }
    }

    /**
     * Creates the staging table (business columns + the internal chunk-id column) if it does not
     * already exist - a no-op on resume, where the table survives from the interrupted attempt.
     */
    public void ensureTableExists(String datasetName, Schema schema) {
        this.columnMappings = schema.getFields().stream()
                .map(field -> ArrowToDuckDbTypeMapper.map(datasetName, field)).toList();
        if (tableExists()) {
            return;
        }
        String columnsDdl = columnMappings.stream()
                .map(c -> quoteIdentifier(c.columnName()) + " " + c.duckDbType())
                .collect(Collectors.joining(", "));
        String ddl = "CREATE TABLE " + quoteIdentifier(tableName) + " (" + columnsDdl + ", "
                + CHUNK_ID_COLUMN + " BIGINT NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            connection.commit();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to create resumable staging table " + tableName + " with DDL: " + ddl, e);
        }
    }

    private boolean tableExists() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to check for existing staging table " + tableName, e);
        }
    }

    /**
     * Begins loading {@code chunkId}: deletes any rows already present for this chunk (a no-op on
     * a fresh chunk, but exactly what makes a retry safe) and opens the Appender for this chunk's
     * rows, all inside the transaction {@link #commitChunk()} will commit.
     */
    public void beginChunk(long chunkId) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM " + quoteIdentifier(tableName) + " WHERE " + CHUNK_ID_COLUMN + " = " + chunkId);
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DuckDbWriteException("Failed to clear prior rows for chunk " + chunkId + " on table " + tableName, e);
        }
        try {
            this.appender = connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, tableName);
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DuckDbWriteException("Failed to open appender for chunk " + chunkId + " on table " + tableName, e);
        }
        this.currentChunkId = chunkId;
        this.currentChunkRows = 0;
        this.currentChunkBytes = 0;
    }

    /** Appends one Arrow batch's rows, tagged with the current chunk id. Does not commit. */
    public long writeBatch(VectorSchemaRoot batch) {
        if (appender == null) {
            throw new IllegalStateException("beginChunk() must be called before writeBatch()");
        }
        int rowCountInBatch = batch.getRowCount();
        List<FieldVector> vectors = batch.getFieldVectors();
        long batchBytes = 0;
        for (FieldVector vector : vectors) {
            for (ArrowBuf buf : vector.getFieldBuffers()) {
                batchBytes += buf.readableBytes();
            }
        }
        try {
            for (int row = 0; row < rowCountInBatch; row++) {
                appender.beginRow();
                for (int col = 0; col < columnMappings.size(); col++) {
                    columnMappings.get(col).appender().append(appender, vectors.get(col), row);
                }
                appender.append(currentChunkId);
                appender.endRow();
            }
            currentChunkRows += rowCountInBatch;
            currentChunkBytes += batchBytes;
            return batchBytes;
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed writing batch for chunk " + currentChunkId + " to table " + tableName
                    + " after " + currentChunkRows + " rows in this chunk", e);
        }
    }

    /**
     * Flushes and closes the Appender, then commits the DELETE + re-inserted rows for this chunk
     * as one atomic transaction. Only after this returns are this chunk's rows durable - the
     * caller must persist the chunk's COMPLETED metadata only after this succeeds (see the
     * two-phase ordering documented in RefreshCoordinator/ResumableRefreshExecutor).
     */
    public long commitChunk() {
        try {
            appender.flush();
            appender.close();
            appender = null;
            connection.commit();
            log.debug("event=resumable-chunk-committed table={} chunkId={} rows={}", tableName, currentChunkId, currentChunkRows);
            return currentChunkRows;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DuckDbWriteException("Failed to commit chunk " + currentChunkId + " on table " + tableName, e);
        }
    }

    /** Rolls back whatever this chunk attempted to write - it remains retryable, and no partial rows survive. */
    public void rollbackChunk() {
        try {
            if (appender != null) {
                try {
                    appender.close();
                } catch (SQLException ignored) {
                    // rolling back regardless
                }
                appender = null;
            }
            connection.rollback();
        } catch (SQLException e) {
            log.warn("event=resumable-chunk-rollback-failed table={} chunkId={}", tableName, currentChunkId, e);
        }
    }

    /** Authoritative row count read directly from DuckDB - never an in-memory running sum. */
    public long countRows() {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + quoteIdentifier(tableName))) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to count rows in table " + tableName, e);
        }
    }

    /** Drops the internal chunk-id column and checkpoints - call only once every chunk is COMPLETED. */
    public void finalizeTable() {
        try {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + quoteIdentifier(tableName) + " DROP COLUMN " + CHUNK_ID_COLUMN);
                statement.execute("CHECKPOINT");
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to finalize resumable staging table " + tableName, e);
        }
    }

    public List<String> columnNames() {
        List<String> names = new ArrayList<>();
        for (ColumnMapping mapping : columnMappings) {
            names.add(mapping.columnName());
        }
        return names;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("event=resumable-writer-rollback-failed table={}", tableName, e);
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        try {
            if (appender != null) {
                try {
                    appender.close();
                } catch (SQLException e) {
                    log.warn("event=resumable-writer-appender-close-failed table={}", tableName, e);
                }
            }
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("event=resumable-writer-connection-close-failed table={}", tableName, e);
            }
        }
    }
}

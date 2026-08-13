package com.enterprise.datacache.feature.cache.duckdb;

import com.enterprise.datacache.feature.cache.arrow.ArrowToDuckDbTypeMapper;
import com.enterprise.datacache.feature.cache.arrow.ColumnMapping;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.exception.DuckDbWriteException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance bulk writer that ingests a stream of Arrow {@link VectorSchemaRoot} batches into
 * a single freshly created table inside one file-backed DuckDB database, using DuckDB's native
 * Appender API (row-oriented, but bypasses SQL parsing/planning entirely, unlike per-row
 * {@code INSERT}). One writer instance is used for exactly one dataset version build.
 */
public class DuckDbDatasetWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DuckDbDatasetWriter.class);
    private static final long FLUSH_EVERY_ROWS = 500_000L;

    private final Path filePath;
    private final String tableName;
    private final DuckDbProperties properties;

    private DuckDBConnection connection;
    private DuckDBAppender appender;
    private List<ColumnMapping> columnMappings;
    private long rowCount;
    private long rowsSinceFlush;

    public DuckDbDatasetWriter(Path filePath, String tableName, DuckDbProperties properties) {
        this.filePath = filePath;
        this.tableName = tableName;
        this.properties = properties;
    }

    /** Creates the DuckDB file and a table matching {@code schema}, then opens the Appender. Call once, before any {@link #writeBatch}. */
    public void begin(Schema schema) {
        this.connection = DuckDbConnectionFactory.open(filePath, properties);
        this.columnMappings = schema.getFields().stream().map(ArrowToDuckDbTypeMapper::map).toList();
        createTable();
        try {
            this.appender = connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, tableName);
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to open DuckDB appender for table " + tableName, e);
        }
    }

    private void createTable() {
        String columnsDdl = columnMappings.stream()
                .map(c -> quoteIdentifier(c.columnName()) + " " + c.duckDbType())
                .collect(Collectors.joining(", "));
        String ddl = "CREATE TABLE " + quoteIdentifier(tableName) + " (" + columnsDdl + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to create DuckDB table " + tableName + " with DDL: " + ddl, e);
        }
    }

    /** Appends every row of {@code batch} to the table. The caller retains ownership of {@code batch}. */
    public void writeBatch(VectorSchemaRoot batch) {
        if (appender == null) {
            throw new IllegalStateException("begin() must be called before writeBatch()");
        }
        int rowCountInBatch = batch.getRowCount();
        List<FieldVector> vectors = batch.getFieldVectors();
        try {
            for (int row = 0; row < rowCountInBatch; row++) {
                appender.beginRow();
                for (int col = 0; col < columnMappings.size(); col++) {
                    columnMappings.get(col).appender().append(appender, vectors.get(col), row);
                }
                appender.endRow();
            }
            rowCount += rowCountInBatch;
            rowsSinceFlush += rowCountInBatch;
            if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                appender.flush();
                rowsSinceFlush = 0;
            }
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed writing batch to DuckDB table " + tableName + " after "
                    + rowCount + " rows", e);
        }
    }

    /** Flushes and closes the appender, checkpoints the database to disk, and returns the total row count written. */
    public long finish() {
        try {
            if (appender != null) {
                appender.flush();
                appender.close();
                appender = null;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CHECKPOINT");
            }
            log.debug("event=duckdb-write-finished table={} rows={}", tableName, rowCount);
            return rowCount;
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to finish/checkpoint DuckDB table " + tableName, e);
        }
    }

    public List<String> columnNames() {
        List<String> names = new ArrayList<>();
        for (ColumnMapping mapping : columnMappings) {
            names.add(mapping.columnName());
        }
        return names;
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        try {
            if (appender != null) {
                try {
                    appender.close();
                } catch (SQLException e) {
                    log.warn("event=duckdb-appender-close-failed table={}", tableName, e);
                }
            }
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.warn("event=duckdb-connection-close-failed table={}", tableName, e);
                }
            }
        }
    }
}

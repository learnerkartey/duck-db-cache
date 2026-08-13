package com.enterprise.datacache.duckdb;

import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.exception.DuckDbWriteException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDriver;

/** Opens file-backed DuckDB connections with the configured memory/thread/temp-directory pragmas applied. */
public final class DuckDbConnectionFactory {

    private DuckDbConnectionFactory() {
    }

    public static DuckDBConnection open(Path filePath, DuckDbProperties properties) {
        try {
            Files.createDirectories(filePath.getParent());
            if (properties.getTempDirectory() != null) {
                Files.createDirectories(Path.of(properties.getTempDirectory()));
            }
        } catch (IOException e) {
            throw new DuckDbWriteException("Failed to create DuckDB storage directories for " + filePath, e);
        }

        DuckDBConnection connection;
        try {
            connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + filePath.toAbsolutePath());
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to open DuckDB file " + filePath, e);
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA memory_limit='" + properties.getMemoryLimit().toMegabytes() + "MB'");
            statement.execute("PRAGMA threads=" + properties.getThreads());
            if (properties.getTempDirectory() != null) {
                statement.execute("SET temp_directory='" + properties.getTempDirectory().replace("'", "''") + "'");
            }
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new DuckDbWriteException("Failed to apply DuckDB pragmas for " + filePath, e);
        }
        return connection;
    }

    /** Opens an existing DuckDB file read-only, for query-time ATTACH. No write pragmas are needed. */
    public static DuckDBConnection openReadOnly(Path filePath) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty(DuckDBDriver.DUCKDB_READONLY_PROPERTY, "true");
            return (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + filePath.toAbsolutePath(), props);
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to open DuckDB file read-only: " + filePath, e);
        }
    }

    private static void closeQuietly(DuckDBConnection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort cleanup after a failed pragma application
        }
    }
}

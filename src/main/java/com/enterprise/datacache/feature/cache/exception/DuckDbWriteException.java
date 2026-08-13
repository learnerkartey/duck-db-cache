package com.enterprise.datacache.feature.cache.exception;

/** Raised for failures writing Arrow batches into a DuckDB dataset file (schema creation, appender I/O, disk). */
public class DuckDbWriteException extends DataCacheException {

    public DuckDbWriteException(String message, Throwable cause) {
        super("DUCKDB_WRITE_ERROR", message, true, cause);
    }

    public DuckDbWriteException(String message) {
        super("DUCKDB_WRITE_ERROR", message, true);
    }
}

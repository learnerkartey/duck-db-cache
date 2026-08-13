package com.enterprise.datacache.feature.cache.exception;

/** Raised for failures talking to Dremio via Arrow Flight SQL (connection, auth, query execution, streaming). */
public class DremioSourceException extends DataCacheException {

    public DremioSourceException(String message, boolean retryable, Throwable cause) {
        super("DREMIO_SOURCE_ERROR", message, retryable, cause);
    }

    public DremioSourceException(String message, boolean retryable) {
        super("DREMIO_SOURCE_ERROR", message, retryable);
    }
}

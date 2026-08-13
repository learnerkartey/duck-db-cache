package com.enterprise.datacache.feature.cache.exception;

/**
 * Base class for all exceptions raised by the data cache. Every exception declares whether the
 * failure is retryable so the refresh retry policy can classify it without inspecting messages
 * or stack traces.
 */
public class DataCacheException extends RuntimeException {

    private final boolean retryable;
    private final String errorCode;

    public DataCacheException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public DataCacheException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

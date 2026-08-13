package com.enterprise.datacache.exception;

/** Raised for structurally invalid {@code data-cache.*} configuration, detected eagerly at startup. Never retryable. */
public class InvalidConfigurationException extends DataCacheException {

    public InvalidConfigurationException(String message) {
        super("INVALID_CONFIGURATION", message, false);
    }

    public InvalidConfigurationException(String message, Throwable cause) {
        super("INVALID_CONFIGURATION", message, false, cause);
    }
}

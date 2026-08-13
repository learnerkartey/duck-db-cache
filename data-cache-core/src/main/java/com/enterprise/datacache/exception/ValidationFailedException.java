package com.enterprise.datacache.exception;

/** Raised when a BUILDING version fails one or more configured validation rules. Never retryable by itself. */
public class ValidationFailedException extends DataCacheException {

    public ValidationFailedException(String message) {
        super("VALIDATION_FAILED", message, false);
    }
}

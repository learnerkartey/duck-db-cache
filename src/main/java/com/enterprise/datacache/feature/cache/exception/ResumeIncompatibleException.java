package com.enterprise.datacache.feature.cache.exception;

/**
 * Raised internally when a BUILDING version cannot be safely resumed - its source schema changed
 * since the last chunk was loaded. Never surfaced as a bare retryable failure: the resumable
 * executor catches this, abandons the incompatible manifest (preserving ACTIVE), and starts a
 * fresh BUILDING version from chunk 1 within the same refresh attempt.
 */
public class ResumeIncompatibleException extends DataCacheException {

    public ResumeIncompatibleException(String reason) {
        super("RESUME_INCOMPATIBLE", reason, false);
    }
}

package com.enterprise.datacache.feature.cache.model;

/** Overall status of a resumable refresh's manifest - independent of the underlying {@link VersionState}. */
public enum RefreshManifestStatus {
    /** Chunk planning/loading in progress; some chunks may be COMPLETED, others PENDING/FAILED/RUNNING. */
    BUILDING,
    /** Every planned chunk reached COMPLETED; the version has moved on to validation/activation. */
    COMPLETED,
    /** Deliberately abandoned because it can no longer be safely resumed (SQL/schema changed, too
     *  old, corrupt file, or resume configuration changed) - a fresh manifest/version replaces it. */
    ABANDONED,
    /** Unrecoverable failure (e.g. non-retryable chunk error, corrupt BUILDING file). */
    FAILED
}

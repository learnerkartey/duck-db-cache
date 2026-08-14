package com.enterprise.datacache.feature.cache.model;

/** High level outcome of a single dataset refresh attempt. */
public enum RefreshOutcome {
    SUCCESS,
    FAILED,
    VALIDATION_FAILED,
    ALREADY_RUNNING,
    DISABLED,
    NEVER_RUN,
    /** A resumable BUILDING version exists but nothing is currently retrying it - safe to resume. */
    PAUSED_RETRYABLE,
    /** {@code resume()} was called but there is no resumable BUILDING version for this dataset. */
    NO_RESUMABLE_BUILD
}

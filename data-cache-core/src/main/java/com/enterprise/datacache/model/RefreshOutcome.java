package com.enterprise.datacache.model;

/** High level outcome of a single dataset refresh attempt. */
public enum RefreshOutcome {
    SUCCESS,
    FAILED,
    VALIDATION_FAILED,
    ALREADY_RUNNING,
    DISABLED,
    NEVER_RUN
}

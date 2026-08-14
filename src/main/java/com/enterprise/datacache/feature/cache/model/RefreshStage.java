package com.enterprise.datacache.feature.cache.model;

/**
 * Current phase of an in-flight (or just-finished) dataset refresh, surfaced in progress logs and
 * the status API so an operator can tell at a glance where time is being spent.
 *
 * <p>{@code CONNECTING_DREMIO} covers both opening the Flight SQL connection and submitting the
 * source query - {@link com.enterprise.datacache.feature.cache.spi.DremioSource#executeQuery}
 * performs both as one blocking call, so they are not separately observable. Likewise
 * {@code STREAMING} covers both receiving Arrow batches and writing them into DuckDB - this
 * implementation performs both synchronously in the same loop, one batch at a time, so there is no
 * meaningful boundary between "transferring" and "writing" to report separately.
 */
public enum RefreshStage {
    CONNECTING_DREMIO,
    WAITING_FOR_FIRST_BATCH,
    STREAMING,
    VALIDATING,
    ACTIVATING,
    CLEANING_UP,
    COMPLETED,
    FAILED
}

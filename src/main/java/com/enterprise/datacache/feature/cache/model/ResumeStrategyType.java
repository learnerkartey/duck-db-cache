package com.enterprise.datacache.feature.cache.model;

/**
 * Deterministic partitioning method used to split a dataset's source query into independently
 * resumable chunks. Every strategy requires a stable, deterministic key - see
 * docs/RESUMABLE-REFRESH-AND-RECOVERY.md for what makes a key "stable" and why that is required
 * for resumability to be correct rather than merely convenient.
 */
public enum ResumeStrategyType {
    /** Contiguous ranges of a numeric, monotonically stable key (e.g. an increasing transaction ID). */
    RANGE,
    /** Contiguous ranges of a date/timestamp key, one chunk per {@code interval}. */
    TIME_RANGE,
    /** A fixed number of buckets from a deterministic hash of a stable key - for datasets with no
     *  natural contiguous ordering. */
    HASH_BUCKET
}

package com.enterprise.datacache.feature.cache.model;

/**
 * How (if at all) a resumable dataset binds every chunk query to the same logical source
 * snapshot, so a resume after a crash reads the same "as of" state as the chunks completed before
 * it rather than silently mixing old and new source data.
 */
public enum SnapshotMode {
    /** No snapshot binding - every chunk query sees whatever the source currently contains. Only
     *  safe with {@code consistency: BEST_EFFORT}. */
    NONE,
    /** A timestamp captured once when the refresh (first) starts, bound into every chunk query
     *  (including resumed ones) as the named parameter configured by {@code snapshot.parameter-name}.
     *  The dataset's source SQL must reference it, e.g. {@code WHERE ingestion_ts <= :cacheAsOf}. */
    AS_OF_VALUE
}

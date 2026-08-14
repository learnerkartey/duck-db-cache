package com.enterprise.datacache.feature.cache.model;

/**
 * Policy for whether a resumable refresh is allowed to proceed when source consistency across
 * chunks/resumes cannot be proven. Correctness of a multi-chunk cache is more important than
 * preserving partial load work - see docs/RESUMABLE-REFRESH-AND-RECOVERY.md.
 */
public enum ConsistencyMode {
    /**
     * Default and recommended for financial/analytical data. Resume is only permitted when every
     * chunk (original and resumed) can be proven to read the same logical source state - in this
     * implementation, that means {@link SnapshotMode#AS_OF_VALUE} is configured. Without it,
     * config validation fails rather than silently allowing an inconsistent resume.
     */
    STRICT_SNAPSHOT,
    /**
     * Accepts the risk that chunks executed at different times (including across a resume) may
     * observe a changing source. Use only when the source is known to be effectively immutable
     * for the duration of a refresh, or when perfect consistency is not required.
     */
    BEST_EFFORT
}

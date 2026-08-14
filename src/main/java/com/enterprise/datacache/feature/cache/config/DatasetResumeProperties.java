package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.model.ConsistencyMode;
import com.enterprise.datacache.feature.cache.model.ResumeStrategyType;
import java.time.Duration;

/**
 * Per-dataset resumable-refresh configuration: {@code data-cache.datasets.<name>.resume.*}. Opt-in
 * per dataset (default {@code false}) because resumability requires the dataset owner to know a
 * genuinely stable, deterministic partition/resume key exists - see
 * docs/RESUMABLE-REFRESH-AND-RECOVERY.md#choosing-a-resume-key. A dataset left at the default
 * simply restarts its BUILDING version from zero after any interruption, exactly like before this
 * feature existed.
 */
public class DatasetResumeProperties {

    private boolean enabled = false;

    private ResumeStrategyType strategy;

    /** Required for RANGE/TIME_RANGE. Must be a valid SQL identifier - validated at startup. */
    private String partitionColumn;

    /** Target rows per chunk for RANGE. Also used as the probe granularity; not used by TIME_RANGE/HASH_BUCKET. */
    private long chunkSize = 500_000L;

    /** Chunk width for TIME_RANGE, e.g. {@code 1d}. Required when {@code strategy: TIME_RANGE}. */
    private Duration interval;

    /** Number of hash buckets for HASH_BUCKET. */
    private int hashBuckets = 128;

    /**
     * Trusted, dataset-configuration-supplied SQL scalar expression producing an integer in
     * {@code [0, hashBuckets)} from the stable key, e.g. {@code MOD(HASH(transaction_id), 128)}.
     * The framework never invents or verifies Dremio-specific hash syntax - the dataset owner
     * supplies and is responsible for an expression that is valid and deterministic for their
     * Dremio source. Required when {@code strategy: HASH_BUCKET}.
     */
    private String hashExpression;

    private SnapshotProperties snapshot = new SnapshotProperties();

    /** Default STRICT_SNAPSHOT: resume is refused (config fails to validate) without a snapshot binding. */
    private ConsistencyMode consistency = ConsistencyMode.STRICT_SNAPSHOT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ResumeStrategyType getStrategy() {
        return strategy;
    }

    public void setStrategy(ResumeStrategyType strategy) {
        this.strategy = strategy;
    }

    public String getPartitionColumn() {
        return partitionColumn;
    }

    public void setPartitionColumn(String partitionColumn) {
        this.partitionColumn = partitionColumn;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public int getHashBuckets() {
        return hashBuckets;
    }

    public void setHashBuckets(int hashBuckets) {
        this.hashBuckets = hashBuckets;
    }

    public String getHashExpression() {
        return hashExpression;
    }

    public void setHashExpression(String hashExpression) {
        this.hashExpression = hashExpression;
    }

    public SnapshotProperties getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(SnapshotProperties snapshot) {
        this.snapshot = snapshot;
    }

    public ConsistencyMode getConsistency() {
        return consistency;
    }

    public void setConsistency(ConsistencyMode consistency) {
        this.consistency = consistency;
    }
}

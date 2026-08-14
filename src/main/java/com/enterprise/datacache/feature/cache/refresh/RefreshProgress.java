package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.model.RefreshStage;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe, in-memory progress counters for exactly one dataset refresh attempt (one
 * dataset/version pair). Rows, batches, and bytes are updated by the single refresh-worker thread
 * driving the load (see {@link RefreshCoordinator}) but read concurrently by the status API and
 * Micrometer gauges, so every counter is either {@code volatile} or an {@link AtomicLong} - there
 * is no unsynchronized shared mutable state. Each retry attempt (and each dataset) gets its own
 * instance via {@link RefreshProgressRegistry#start}; nothing is ever shared across datasets or
 * across attempts.
 *
 * <p>Rows are only ever added via {@link #recordBatch}, which callers must invoke strictly after a
 * batch has been successfully written to DuckDB - never speculatively before, and never estimated
 * from batch counts.
 */
public final class RefreshProgress {

    private final String datasetName;
    private volatile long version;
    private final RefreshTrigger trigger;
    private final Long previousVersionRowCount;
    private final Long expectedRowCount;
    private final LongSupplier nanoClock;
    private final long startNanos;

    private volatile RefreshStage stage = RefreshStage.CONNECTING_DREMIO;
    private final AtomicLong rowsProcessed = new AtomicLong();
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong bytesProcessed = new AtomicLong();
    private volatile long timeToFirstBatchMs = -1;
    private volatile long completedNanos = -1;

    // Resumable-refresh-only fields (see ResumableRefreshExecutor); unused/left at defaults for a
    // dataset with resume disabled, since it has no chunk concept at all.
    private final AtomicLong rowsRead = new AtomicLong();
    private volatile boolean resuming = false;
    private volatile Long totalChunks;
    private volatile long completedChunks;
    private volatile Long currentChunk;
    private volatile Long failedChunk;

    // Only ever touched by the single refresh-worker thread that also calls recordBatch/checkThreshold
    // for this instance - synchronized purely for defensive clarity, not because of real contention.
    private long lastCheckpointRows;
    private long lastCheckpointNanos;

    public RefreshProgress(String datasetName, long version, RefreshTrigger trigger, Long previousVersionRowCount,
            Long expectedRowCount) {
        this(datasetName, version, trigger, previousVersionRowCount, expectedRowCount, System::nanoTime);
    }

    RefreshProgress(String datasetName, long version, RefreshTrigger trigger, Long previousVersionRowCount,
            Long expectedRowCount, LongSupplier nanoClock) {
        this.datasetName = datasetName;
        this.version = version;
        this.trigger = trigger;
        this.previousVersionRowCount = previousVersionRowCount;
        this.expectedRowCount = expectedRowCount;
        this.nanoClock = nanoClock;
        this.startNanos = nanoClock.getAsLong();
        this.lastCheckpointNanos = startNanos;
    }

    public String datasetName() {
        return datasetName;
    }

    public long version() {
        return version;
    }

    /**
     * Corrects the tracked version when a resumable refresh's initial guess (the currently BUILDING
     * manifest's version, or the next allocable version if none exists) turns out to be wrong -
     * only possible when a resume candidate is discovered mid-decision to be incompatible and a
     * fresh version is allocated instead. A no-op change of identity, not of accumulated counters.
     */
    public void updateVersion(long newVersion) {
        this.version = newVersion;
    }

    public RefreshTrigger trigger() {
        return trigger;
    }

    public RefreshStage currentStage() {
        return stage;
    }

    /** Advances the stage. Reaching a terminal stage ({@code COMPLETED}/{@code FAILED}) freezes {@link #elapsedMs()}. */
    public void stage(RefreshStage newStage) {
        this.stage = newStage;
        if (newStage == RefreshStage.COMPLETED || newStage == RefreshStage.FAILED) {
            completedNanos = nanoClock.getAsLong();
        }
    }

    public void recordFirstBatch(long timeToFirstBatchMs) {
        this.timeToFirstBatchMs = timeToFirstBatchMs;
    }

    /** -1 until the first batch has actually arrived. */
    public long timeToFirstBatchMs() {
        return timeToFirstBatchMs;
    }

    /** Records a batch that has already been successfully persisted to DuckDB. */
    public void recordBatch(long rowsInBatch, long bytesInBatch) {
        rowsProcessed.addAndGet(rowsInBatch);
        batchesProcessed.incrementAndGet();
        bytesProcessed.addAndGet(bytesInBatch);
    }

    public long rowsProcessed() {
        return rowsProcessed.get();
    }

    public long batchesProcessed() {
        return batchesProcessed.get();
    }

    public long bytesProcessed() {
        return bytesProcessed.get();
    }

    /** Rows streamed from the source so far, whether or not they have been durably committed yet - see {@link #rowsProcessed()} for the durable count. */
    public void recordRead(long rowsInBatch) {
        rowsRead.addAndGet(rowsInBatch);
    }

    public long rowsRead() {
        return rowsRead.get();
    }

    /** Marks this attempt as resuming a previously interrupted BUILDING version rather than starting fresh. */
    public void markResuming(long completedChunks, Long totalChunks, long rowsAlreadyCommitted) {
        this.resuming = true;
        this.completedChunks = completedChunks;
        this.totalChunks = totalChunks;
        this.rowsProcessed.set(rowsAlreadyCommitted);
    }

    public boolean isResuming() {
        return resuming;
    }

    public void updateChunkProgress(long completedChunks, Long totalChunks, Long currentChunk) {
        this.completedChunks = completedChunks;
        this.totalChunks = totalChunks;
        this.currentChunk = currentChunk;
    }

    public void recordChunkFailed(long chunkId) {
        this.failedChunk = chunkId;
    }

    public void clearFailedChunk() {
        this.failedChunk = null;
    }

    public Long totalChunks() {
        return totalChunks;
    }

    public long completedChunks() {
        return completedChunks;
    }

    public Long currentChunk() {
        return currentChunk;
    }

    public Long failedChunk() {
        return failedChunk;
    }

    /** Elapsed time since this attempt started; frozen once a terminal stage is reached rather than growing forever. */
    public long elapsedMs() {
        long endNanos = completedNanos >= 0 ? completedNanos : nanoClock.getAsLong();
        return (endNanos - startNanos) / 1_000_000L;
    }

    public double averageRowsPerSecond() {
        long elapsed = elapsedMs();
        return elapsed <= 0 ? 0.0 : (rowsProcessed() * 1000.0) / elapsed;
    }

    public Long previousVersionRowCount() {
        return previousVersionRowCount;
    }

    public Long expectedRowCount() {
        return expectedRowCount;
    }

    /**
     * Percentage complete, ALWAYS an estimate: based on the configured
     * {@code data-cache.datasets.<name>.progress.expected-row-count} if present, otherwise the
     * previous ACTIVE version's row count, otherwise {@code null} (no basis available). The new
     * version's actual final row count may differ from either basis, so this is never presented as
     * an exact percentage.
     */
    public Double estimatedPercent() {
        Long basis = expectedRowCount != null ? expectedRowCount : previousVersionRowCount;
        if (basis == null || basis <= 0) {
            return null;
        }
        return (rowsProcessed() * 100.0) / basis;
    }

    /**
     * Checks whether a progress log line is due now - {@code rowInterval} rows processed since the
     * last checkpoint, OR {@code timeInterval} elapsed since the last checkpoint, whichever comes
     * first. If so, atomically advances the checkpoint and returns a {@link Checkpoint} snapshot
     * (including throughput observed since the previous checkpoint); otherwise returns empty with
     * no side effects, so callers never log more often than configured.
     */
    public synchronized Optional<Checkpoint> checkThreshold(long rowInterval, Duration timeInterval) {
        long rows = rowsProcessed();
        long nowNanos = nanoClock.getAsLong();
        boolean rowThresholdMet = rowInterval > 0 && (rows - lastCheckpointRows) >= rowInterval;
        boolean timeThresholdMet = timeInterval != null && !timeInterval.isZero() && !timeInterval.isNegative()
                && (nowNanos - lastCheckpointNanos) >= timeInterval.toNanos();
        if (!rowThresholdMet && !timeThresholdMet) {
            return Optional.empty();
        }

        long intervalRows = rows - lastCheckpointRows;
        long intervalNanos = nowNanos - lastCheckpointNanos;
        double intervalRowsPerSecond = intervalNanos <= 0 ? 0.0 : (intervalRows * 1_000_000_000.0) / intervalNanos;

        lastCheckpointRows = rows;
        lastCheckpointNanos = nowNanos;

        return Optional.of(new Checkpoint(rowsProcessed(), batchesProcessed(), bytesProcessed(), elapsedMs(),
                averageRowsPerSecond(), intervalRowsPerSecond, stage, resuming, completedChunks, totalChunks));
    }

    /** Immutable snapshot of running totals plus the throughput observed since the previous checkpoint. */
    public record Checkpoint(long rowsProcessed, long batchesProcessed, long bytesProcessed, long elapsedMs,
            double averageRowsPerSecond, double intervalRowsPerSecond, RefreshStage stage, boolean resuming,
            long completedChunks, Long totalChunks) {
    }
}

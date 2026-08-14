package com.enterprise.datacache.feature.cache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.feature.cache.model.RefreshStage;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit-level tests of {@link RefreshProgress} in isolation - no DremioSource, no DuckDB file, no
 * real refresh pipeline - using an injectable fake nanosecond clock so time-based threshold tests
 * run instantly instead of sleeping.
 */
class RefreshProgressTest {

    /** Mutable fake clock: advance it explicitly instead of sleeping real wall-clock time. */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong(0);

        long get() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    private RefreshProgress progress(FakeClock clock, Long previousVersionRowCount, Long expectedRowCount) {
        return new RefreshProgress("financial", 13, RefreshTrigger.MANUAL, previousVersionRowCount, expectedRowCount,
                clock::get);
    }

    @Test
    void rowAndBatchCountersIncrementAccuratelyAcrossMultipleUnevenBatches() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        progress.recordBatch(1000, 50_000);
        progress.recordBatch(250, 12_500); // batches are not assumed to be the same size
        progress.recordBatch(4000, 200_000);

        assertThat(progress.rowsProcessed()).isEqualTo(5250);
        assertThat(progress.batchesProcessed()).isEqualTo(3);
        assertThat(progress.bytesProcessed()).isEqualTo(262_500);
    }

    @Test
    void noProgressLogBeforeEitherThresholdIsCrossed() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        progress.recordBatch(500, 1000);
        clock.advance(Duration.ofSeconds(5));

        Optional<RefreshProgress.Checkpoint> checkpoint = progress.checkThreshold(1_000_000, Duration.ofSeconds(30));
        assertThat(checkpoint).isEmpty();
    }

    @Test
    void logsWhenRowThresholdIsCrossedEvenIfTimeThresholdIsNot() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        progress.recordBatch(1_000_000, 1);
        clock.advance(Duration.ofMillis(1)); // far below the 30s time threshold

        Optional<RefreshProgress.Checkpoint> checkpoint = progress.checkThreshold(1_000_000, Duration.ofSeconds(30));
        assertThat(checkpoint).isPresent();
        assertThat(checkpoint.get().rowsProcessed()).isEqualTo(1_000_000);
    }

    @Test
    void logsWhenTimeThresholdIsCrossedEvenIfRowThresholdIsNot() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        progress.recordBatch(10, 1); // far below the 1,000,000-row threshold
        clock.advance(Duration.ofSeconds(31));

        Optional<RefreshProgress.Checkpoint> checkpoint = progress.checkThreshold(1_000_000, Duration.ofSeconds(30));
        assertThat(checkpoint).isPresent();
        assertThat(checkpoint.get().rowsProcessed()).isEqualTo(10);
    }

    @Test
    void checkpointResetsSoASecondImmediateCheckDoesNotFireAgain() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        progress.recordBatch(1_000_000, 1);
        assertThat(progress.checkThreshold(1_000_000, Duration.ofSeconds(30))).isPresent();

        // No new rows and no time elapsed since the checkpoint - must not fire again.
        assertThat(progress.checkThreshold(1_000_000, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void averageAndIntervalRatesAreComputedSeparatelyAndCorrectly() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        clock.advance(Duration.ofSeconds(10));
        progress.recordBatch(100_000, 1);
        Optional<RefreshProgress.Checkpoint> first = progress.checkThreshold(1, Duration.ofSeconds(1));
        assertThat(first).isPresent();
        // 100,000 rows over 10s elapsed, both average and interval rate agree at the first checkpoint.
        assertThat(first.get().averageRowsPerSecond()).isCloseTo(10_000.0, org.assertj.core.data.Offset.offset(1.0));
        assertThat(first.get().intervalRowsPerSecond()).isCloseTo(10_000.0, org.assertj.core.data.Offset.offset(1.0));

        // A much faster second interval: interval rate should reflect only the recent burst, while
        // average blends it with the slower start.
        clock.advance(Duration.ofSeconds(1));
        progress.recordBatch(100_000, 1);
        Optional<RefreshProgress.Checkpoint> second = progress.checkThreshold(1, Duration.ofSeconds(1));
        assertThat(second).isPresent();
        assertThat(second.get().intervalRowsPerSecond()).isCloseTo(100_000.0, org.assertj.core.data.Offset.offset(1.0));
        assertThat(second.get().averageRowsPerSecond()).isLessThan(second.get().intervalRowsPerSecond());
    }

    @Test
    void elapsedTimeFreezesOnceATerminalStageIsReached() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);

        clock.advance(Duration.ofSeconds(5));
        progress.stage(RefreshStage.COMPLETED);
        long elapsedAtCompletion = progress.elapsedMs();

        clock.advance(Duration.ofSeconds(100)); // simulates real time passing after the refresh ended
        assertThat(progress.elapsedMs()).isEqualTo(elapsedAtCompletion);
    }

    @Test
    void estimatedPercentPrefersExpectedRowCountOverPreviousVersionWhenBothAreSet() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, 31_000_000L, 30_000_000L);
        progress.recordBatch(15_000_000, 1);

        assertThat(progress.estimatedPercent()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void estimatedPercentFallsBackToPreviousVersionRowCountWhenNoExpectedRowCountIsConfigured() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, 31_284_901L, null);
        progress.recordBatch(15_500_000, 1);

        assertThat(progress.estimatedPercent()).isCloseTo(49.5, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void estimatedPercentIsAbsentWithNoBasisAvailable() {
        FakeClock clock = new FakeClock();
        RefreshProgress progress = progress(clock, null, null);
        progress.recordBatch(1000, 1);

        assertThat(progress.estimatedPercent()).isNull();
    }

    @Test
    void twoDatasetsTrackedByTheSameRegistryMaintainCompletelyIsolatedCounters() {
        RefreshProgressRegistry registry = new RefreshProgressRegistry(
                new com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        RefreshProgress financial = registry.start("financial", 13, RefreshTrigger.SCHEDULED, null, null);
        RefreshProgress headcount = registry.start("headcount", 8, RefreshTrigger.MANUAL, null, null);

        financial.recordBatch(12_000_000, 1);
        headcount.recordBatch(7_000_000, 1);
        financial.recordBatch(500_000, 1);

        assertThat(registry.find("financial").orElseThrow().rowsProcessed()).isEqualTo(12_500_000);
        assertThat(registry.find("headcount").orElseThrow().rowsProcessed()).isEqualTo(7_000_000);
        assertThat(registry.find("financial").orElseThrow().version()).isEqualTo(13);
        assertThat(registry.find("headcount").orElseThrow().version()).isEqualTo(8);

        // Starting a new attempt for one dataset must never disturb the other dataset's counters.
        RefreshProgress financialRetry = registry.start("financial", 14, RefreshTrigger.SCHEDULED, null, null);
        assertThat(financialRetry.rowsProcessed()).isEqualTo(0);
        assertThat(registry.find("headcount").orElseThrow().rowsProcessed()).isEqualTo(7_000_000);
    }
}

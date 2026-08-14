package com.enterprise.datacache.feature.cache.refresh;

import com.enterprise.datacache.feature.cache.config.ProgressLoggingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the rate-limited {@code event=dataset-load-progress} log line, shared by the plain
 * single-pass refresh path ({@link RefreshCoordinator}) and the chunked resumable path
 * ({@code ResumableRefreshExecutor}) so both produce an identical log shape - with resume-specific
 * fields ({@code resume}, {@code completedChunks}, {@code totalChunks}, {@code rowsCommitted})
 * added automatically whenever {@link RefreshProgress} carries chunk information.
 */
public final class RefreshProgressLogger {

    private static final Logger log = LoggerFactory.getLogger(RefreshCoordinator.class);

    private RefreshProgressLogger() {
    }

    /** Checks the row/time threshold and, if due, emits one INFO line. {@code fileSizeBytes} is only invoked when a line is actually about to be logged. */
    public static void logIfDue(RefreshProgress progress, ProgressLoggingProperties config, LongSupplier fileSizeBytes) {
        if (!config.isEnabled()) {
            return;
        }
        progress.checkThreshold(config.getRowInterval(), config.getTimeInterval())
                .ifPresent(checkpoint -> emit(progress, checkpoint, config, fileSizeBytes));
    }

    private static void emit(RefreshProgress progress, RefreshProgress.Checkpoint checkpoint,
            ProgressLoggingProperties config, LongSupplier fileSizeBytes) {
        StringBuilder format = new StringBuilder(
                "event=dataset-load-progress dataset={} version={} status=BUILDING rowsProcessed={} elapsedMs={} "
                        + "averageRowsPerSecond={} currentRowsPerSecond={}");
        List<Object> args = new ArrayList<>(List.of(
                progress.datasetName(), progress.version(), checkpoint.rowsProcessed(), checkpoint.elapsedMs(),
                fmt(checkpoint.averageRowsPerSecond()), fmt(checkpoint.intervalRowsPerSecond())));

        if (checkpoint.totalChunks() != null) {
            // rowsProcessed above IS rowsCommitted for a resumable dataset - recordBatch() is only
            // ever called once a chunk has actually committed - but the resume example shape names
            // it explicitly so operators grepping logs see the durability guarantee, not just a
            // generic counter name.
            format.append(" resume={} rowsCommitted={} completedChunks={} totalChunks={}");
            args.add(checkpoint.resuming());
            args.add(checkpoint.rowsProcessed());
            args.add(checkpoint.completedChunks());
            args.add(checkpoint.totalChunks());
            long rowsRead = progress.rowsRead();
            if (rowsRead != checkpoint.rowsProcessed()) {
                format.append(" rowsRead={}"); // in-flight, not yet durably committed - see item 32
                args.add(rowsRead);
            }
        }

        if (config.isIncludeBatchCount()) {
            format.append(" batchesProcessed={}");
            args.add(checkpoint.batchesProcessed());
        }
        if (checkpoint.bytesProcessed() > 0) {
            double elapsedSeconds = checkpoint.elapsedMs() / 1000.0;
            double mbPerSec = elapsedSeconds > 0 ? (checkpoint.bytesProcessed() / (1024.0 * 1024.0)) / elapsedSeconds : 0.0;
            format.append(" bytesProcessed={} MBPerSec={}");
            args.add(checkpoint.bytesProcessed());
            args.add(fmt(mbPerSec));
        }
        if (config.isIncludeFileSize()) {
            format.append(" duckDbFileSizeBytes={}");
            args.add(fileSizeBytes.getAsLong());
        }
        Double estimatedPercent = progress.estimatedPercent();
        if (estimatedPercent != null) {
            if (progress.expectedRowCount() != null) {
                format.append(" expectedRowCount={}");
                args.add(progress.expectedRowCount());
            } else {
                format.append(" previousVersionRows={}");
                args.add(progress.previousVersionRowCount());
            }
            format.append(" estimatedPercent={}");
            args.add(fmt(estimatedPercent));
        }

        log.info(format.toString(), args.toArray());
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }
}

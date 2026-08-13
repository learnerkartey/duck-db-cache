package com.enterprise.datacache.benchmark;

import java.nio.file.Path;

/** Real, measured output of one benchmark run. Every field comes from an actual execution - never fabricated. */
public record BenchmarkResult(long rowCount, long elapsedMs, long fileSizeBytes, Path outputFile) {

    public double rowsPerSecond() {
        return elapsedMs <= 0 ? 0.0 : (rowCount * 1000.0) / elapsedMs;
    }

    public double megabytesPerSecond() {
        return elapsedMs <= 0 ? 0.0 : (fileSizeBytes / (1024.0 * 1024.0)) / (elapsedMs / 1000.0);
    }

    public String report() {
        return """
                DuckDB Writer Benchmark Result
                -------------------------------
                Rows written      : %,d
                Elapsed           : %,d ms (%.2f s)
                Rows/sec          : %,.0f
                Output file       : %s
                File size         : %,d bytes (%.2f MB)
                MB/sec            : %.2f
                """.formatted(rowCount, elapsedMs, elapsedMs / 1000.0, rowsPerSecond(), outputFile,
                fileSizeBytes, fileSizeBytes / (1024.0 * 1024.0), megabytesPerSecond());
    }
}

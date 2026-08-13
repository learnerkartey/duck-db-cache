package com.enterprise.datacache.benchmark;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line parameters for {@link DuckDbWriterBenchmark}. Supports {@code --key=value} args;
 * unrecognized keys are rejected so typos surface immediately instead of silently using defaults.
 */
public record BenchmarkParameters(long rowCount, int batchSize, Path outputFile, int extraColumnCount) {

    private static final long DEFAULT_ROW_COUNT = 5_000_000L;
    private static final int DEFAULT_BATCH_SIZE = 100_000;
    private static final int DEFAULT_EXTRA_COLUMNS = 8;

    public static BenchmarkParameters parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Unrecognized argument '" + arg
                        + "'. Expected --key=value, e.g. --rows=5000000 --batch-size=100000 --output=/tmp/bench.duckdb --columns=8");
            }
            int eq = arg.indexOf('=');
            options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        long rows = options.containsKey("rows") ? Long.parseLong(options.remove("rows")) : DEFAULT_ROW_COUNT;
        int batchSize = options.containsKey("batch-size") ? Integer.parseInt(options.remove("batch-size")) : DEFAULT_BATCH_SIZE;
        int columns = options.containsKey("columns") ? Integer.parseInt(options.remove("columns")) : DEFAULT_EXTRA_COLUMNS;
        Path output = options.containsKey("output")
                ? Path.of(options.remove("output"))
                : Path.of(System.getProperty("java.io.tmpdir"), "data-cache-benchmark", "benchmark_" + System.currentTimeMillis() + ".duckdb");
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("Unrecognized benchmark options: " + options.keySet());
        }
        return new BenchmarkParameters(rows, batchSize, output, columns);
    }
}

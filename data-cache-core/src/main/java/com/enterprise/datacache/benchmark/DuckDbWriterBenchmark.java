package com.enterprise.datacache.benchmark;

import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.duckdb.DuckDbDatasetWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Local, standalone benchmark of the production {@link DuckDbDatasetWriter} bulk-ingestion path.
 * Generates synthetic Arrow batches directly (no Java row/DTO objects, matching the same
 * bounded-memory streaming shape used by the real refresh pipeline) and measures real elapsed
 * time, rows/sec, and MB/sec writing them through the exact same Appender-based writer used in
 * production.
 *
 * <p>Run via Gradle: {@code ./gradlew :data-cache-core:runBenchmark --args="--rows=5000000 --batch-size=100000"}
 * or directly: {@code java -cp ... com.enterprise.datacache.benchmark.DuckDbWriterBenchmark --rows=5000000}
 */
public final class DuckDbWriterBenchmark {

    private DuckDbWriterBenchmark() {
    }

    public static void main(String[] args) {
        BenchmarkParameters params = BenchmarkParameters.parse(args);
        System.out.println("Starting DuckDB writer benchmark: rows=" + params.rowCount()
                + " batchSize=" + params.batchSize() + " extraColumns=" + params.extraColumnCount()
                + " output=" + params.outputFile());
        BenchmarkResult result = run(params);
        System.out.println(result.report());
    }

    public static BenchmarkResult run(BenchmarkParameters params) {
        Schema schema = buildSchema(params.extraColumnCount());
        DuckDbProperties duckDbProperties = new DuckDbProperties();
        try {
            Files.createDirectories(params.outputFile().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create benchmark output directory", e);
        }
        duckDbProperties.setTempDirectory(params.outputFile().getParent().resolve("temp").toString());

        long startNanos = System.nanoTime();
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                DuckDbDatasetWriter writer = new DuckDbDatasetWriter(params.outputFile(), "benchmark", duckDbProperties)) {
            writer.begin(schema);

            long written = 0;
            while (written < params.rowCount()) {
                int rowsInBatch = (int) Math.min(params.batchSize(), params.rowCount() - written);
                try (VectorSchemaRoot batch = VectorSchemaRoot.create(schema, allocator)) {
                    batch.allocateNew();
                    populateBatch(batch, written, rowsInBatch, params.extraColumnCount());
                    batch.setRowCount(rowsInBatch);
                    writer.writeBatch(batch);
                }
                written += rowsInBatch;
            }
            writer.finish();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        long fileSize;
        try {
            fileSize = Files.size(params.outputFile());
        } catch (IOException e) {
            fileSize = 0L;
        }
        return new BenchmarkResult(params.rowCount(), elapsedMs, fileSize, params.outputFile());
    }

    private static Schema buildSchema(int extraColumnCount) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", FieldType.notNullable(new ArrowType.Int(64, true)), null));
        fields.add(new Field("name", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("amount", FieldType.nullable(new ArrowType.Decimal(12, 2, 128)), null));
        fields.add(new Field("created_at", FieldType.nullable(new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)), null));
        for (int i = 0; i < extraColumnCount; i++) {
            fields.add(new Field("extra_col_" + i, FieldType.nullable(new ArrowType.Int(32, true)), null));
        }
        return new Schema(fields);
    }

    private static void populateBatch(VectorSchemaRoot batch, long startId, int rowCount, int extraColumnCount) {
        BigIntVector id = (BigIntVector) batch.getVector("id");
        VarCharVector name = (VarCharVector) batch.getVector("name");
        DecimalVector amount = (DecimalVector) batch.getVector("amount");
        TimeStampMicroVector createdAt = (TimeStampMicroVector) batch.getVector("created_at");
        List<IntVector> extraVectors = new ArrayList<>(extraColumnCount);
        for (int i = 0; i < extraColumnCount; i++) {
            extraVectors.add((IntVector) batch.getVector("extra_col_" + i));
        }

        long nowMicros = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
        for (int i = 0; i < rowCount; i++) {
            long rowId = startId + i;
            id.setSafe(i, rowId);
            name.setSafe(i, ("synthetic-row-" + rowId).getBytes());
            amount.setSafe(i, BigDecimal.valueOf((rowId % 100_000) / 100.0).setScale(2, java.math.RoundingMode.HALF_UP));
            createdAt.setSafe(i, nowMicros + rowId);
            for (int c = 0; c < extraColumnCount; c++) {
                extraVectors.get(c).setSafe(i, (int) ((rowId + c) % 1000));
            }
        }
        for (FieldVector v : batch.getFieldVectors()) {
            v.setValueCount(rowCount);
        }
    }
}

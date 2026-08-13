package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.math.BigDecimal;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Test-only {@link DremioSource} that generates synthetic {@code (id INT, name VARCHAR, amount DECIMAL(12,2))}
 * batches entirely in memory - no network, no real Dremio. Used to exercise the refresh pipeline,
 * versioning, and query engine (including the core-embedding acceptance test) without any
 * production fake implementations existing outside {@code src/test}.
 */
public class InMemoryDremioSource implements DremioSource {

    public static final Schema SCHEMA = new Schema(List.of(
            new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null),
            new Field("name", FieldType.nullable(new ArrowType.Utf8()), null),
            new Field("amount", FieldType.nullable(new ArrowType.Decimal(12, 2, 128)), null)));

    private final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
    private final long totalRows;
    private final int batchSize;
    private final RuntimeException failureOnExecute;

    public InMemoryDremioSource(long totalRows, int batchSize) {
        this(totalRows, batchSize, null);
    }

    public InMemoryDremioSource(long totalRows, int batchSize, RuntimeException failureOnExecute) {
        this.totalRows = totalRows;
        this.batchSize = batchSize;
        this.failureOnExecute = failureOnExecute;
    }

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        if (failureOnExecute != null) {
            throw failureOnExecute;
        }
        return new InMemoryArrowBatchStream(allocator.newChildAllocator("test-query", 0, Long.MAX_VALUE), totalRows, batchSize);
    }

    @Override
    public boolean isHealthy() {
        return failureOnExecute == null;
    }

    @Override
    public void close() {
        allocator.close();
    }

    private static final class InMemoryArrowBatchStream implements ArrowBatchStream {

        private final BufferAllocator allocator;
        private final long totalRows;
        private final int batchSize;
        private long emitted = 0;
        private VectorSchemaRoot current;

        InMemoryArrowBatchStream(BufferAllocator allocator, long totalRows, int batchSize) {
            this.allocator = allocator;
            this.totalRows = totalRows;
            this.batchSize = batchSize;
        }

        @Override
        public Schema schema() {
            return SCHEMA;
        }

        @Override
        public boolean loadNextBatch() {
            if (current != null) {
                current.close();
                current = null;
            }
            if (emitted >= totalRows) {
                return false;
            }
            int rowsInBatch = (int) Math.min(batchSize, totalRows - emitted);
            current = VectorSchemaRoot.create(SCHEMA, allocator);
            current.allocateNew();
            IntVector id = (IntVector) current.getVector("id");
            VarCharVector name = (VarCharVector) current.getVector("name");
            DecimalVector amount = (DecimalVector) current.getVector("amount");
            for (int i = 0; i < rowsInBatch; i++) {
                long rowNum = emitted + i;
                id.setSafe(i, (int) rowNum);
                name.setSafe(i, ("row-" + rowNum).getBytes());
                amount.setSafe(i, BigDecimal.valueOf(rowNum * 1.5).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            current.setRowCount(rowsInBatch);
            emitted += rowsInBatch;
            return true;
        }

        @Override
        public VectorSchemaRoot currentBatch() {
            return current;
        }

        @Override
        public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
            allocator.close();
        }
    }
}

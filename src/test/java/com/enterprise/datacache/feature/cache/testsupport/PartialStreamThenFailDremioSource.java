package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.math.BigDecimal;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Test-only {@link DremioSource} that streams {@code rowsBeforeFailure} real rows (via the same
 * {@code (id, name, amount)} shape as {@link InMemoryDremioSource}) across several successful
 * batches, then throws on the next {@link ArrowBatchStream#loadNextBatch()} call. Used to prove
 * that a mid-stream failure still leaves an accurate {@code rowsProcessed} count behind for the
 * rows that were genuinely written before the failure - never zero, never estimated.
 */
public class PartialStreamThenFailDremioSource implements DremioSource {

    private final long rowsBeforeFailure;
    private final int batchSize;
    private final RuntimeException failure;
    private final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);

    public PartialStreamThenFailDremioSource(long rowsBeforeFailure, int batchSize, RuntimeException failure) {
        this.rowsBeforeFailure = rowsBeforeFailure;
        this.batchSize = batchSize;
        this.failure = failure;
    }

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        return new PartialStream(allocator.newChildAllocator("partial-stream-then-fail", 0, Long.MAX_VALUE));
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public void close() {
        allocator.close();
    }

    private final class PartialStream implements ArrowBatchStream {

        private final BufferAllocator streamAllocator;
        private long emitted = 0;
        private VectorSchemaRoot current;

        PartialStream(BufferAllocator streamAllocator) {
            this.streamAllocator = streamAllocator;
        }

        @Override
        public Schema schema() {
            return InMemoryDremioSource.SCHEMA;
        }

        @Override
        public boolean loadNextBatch() {
            if (current != null) {
                current.close();
                current = null;
            }
            if (emitted >= rowsBeforeFailure) {
                throw failure;
            }
            int rowsInBatch = (int) Math.min(batchSize, rowsBeforeFailure - emitted);
            current = VectorSchemaRoot.create(InMemoryDremioSource.SCHEMA, streamAllocator);
            current.allocateNew();
            IntVector id = (IntVector) current.getVector("id");
            VarCharVector name = (VarCharVector) current.getVector("name");
            DecimalVector amount = (DecimalVector) current.getVector("amount");
            for (int i = 0; i < rowsInBatch; i++) {
                long rowNum = emitted + i;
                id.setSafe(i, (int) rowNum);
                name.setSafe(i, ("row-" + rowNum).getBytes());
                amount.setSafe(i, BigDecimal.valueOf(rowNum).setScale(2, java.math.RoundingMode.HALF_UP));
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
            streamAllocator.close();
        }
    }
}

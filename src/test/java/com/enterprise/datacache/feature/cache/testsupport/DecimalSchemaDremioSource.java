package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.math.BigDecimal;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Test-only {@link DremioSource} that emits a single {@code (id INT, amount DECIMAL(precision,scale))}
 * batch with exactly one row, for a caller-supplied precision/scale/value. Used to prove the
 * decimal schema evolution policy: instantiating this twice with different precision/scale for
 * consecutive refreshes of the same dataset simulates a source schema that widened between
 * refreshes (e.g. Dremio changing a column from {@code DECIMAL(18,3)} to {@code DECIMAL(38,9)}),
 * exactly the way real Dremio schema drift would appear to the refresh pipeline - the schema
 * source of truth is only ever {@link ArrowBatchStream#schema()} for the CURRENT refresh.
 */
public class DecimalSchemaDremioSource implements DremioSource {

    private final int precision;
    private final int scale;
    private final BigDecimal value;
    private final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);

    public DecimalSchemaDremioSource(int precision, int scale, BigDecimal value) {
        this.precision = precision;
        this.scale = scale;
        this.value = value;
    }

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        Schema schema = new Schema(List.of(
                new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null),
                new Field("amount", FieldType.nullable(new ArrowType.Decimal(precision, scale, 128)), null)));
        return new SingleRowStream(schema, allocator.newChildAllocator("decimal-schema-test", 0, Long.MAX_VALUE));
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public void close() {
        allocator.close();
    }

    private final class SingleRowStream implements ArrowBatchStream {

        private final Schema schema;
        private final BufferAllocator streamAllocator;
        private VectorSchemaRoot current;
        private boolean emitted;

        SingleRowStream(Schema schema, BufferAllocator streamAllocator) {
            this.schema = schema;
            this.streamAllocator = streamAllocator;
        }

        @Override
        public Schema schema() {
            return schema;
        }

        @Override
        public boolean loadNextBatch() {
            if (emitted) {
                return false;
            }
            current = VectorSchemaRoot.create(schema, streamAllocator);
            current.allocateNew();
            ((IntVector) current.getVector("id")).setSafe(0, 1);
            ((DecimalVector) current.getVector("amount")).setSafe(0, value);
            current.setRowCount(1);
            emitted = true;
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

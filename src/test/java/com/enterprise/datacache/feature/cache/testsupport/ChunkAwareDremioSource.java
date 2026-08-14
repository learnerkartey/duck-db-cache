package com.enterprise.datacache.feature.cache.testsupport;

import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Test-only {@link DremioSource} that actually honors the {@code WHERE id >= start AND id <= end}
 * predicate {@code RangePartitionStrategy} generates - unlike {@link InMemoryDremioSource}, which
 * ignores its SQL argument entirely and always emits the same full range. This is what makes it
 * possible to exercise real chunk-by-chunk resumable loading in tests: each chunk query returns
 * only the rows that actually belong to that chunk, exactly like a real chunked source table would.
 *
 * <p>Rows are {@code (id INT, amount DECIMAL)} with ids {@code 1..totalRows} inclusive. The MIN/MAX
 * probe query {@code RangePartitionStrategy} issues (detected by the literal {@code __cache_min}
 * alias it always uses) is answered from {@code totalRows} directly, never by scanning rows.
 *
 * <p>{@link #failChunkStartingAt} lets a test simulate a crash at a specific point in a specific
 * chunk's stream - after N Arrow batches have been successfully read (simulating a mid-chunk crash
 * with partial, uncommitted progress) or immediately (N=0, simulating a crash before any row of that
 * chunk was streamed). Each armed failure is one-shot: the next request for that same chunk start
 * succeeds normally, modelling a retry after the simulated crash.
 */
public class ChunkAwareDremioSource implements DremioSource {

    private static final Pattern RANGE_PATTERN =
            Pattern.compile(">=\\s*(-?\\d+)\\s+AND\\s+\\S+\\s*<=\\s*(-?\\d+)");

    public static Schema decimalSchema(int precision, int scale) {
        return new Schema(List.of(
                new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null),
                new Field("amount", FieldType.nullable(new ArrowType.Decimal(precision, scale, 128)), null)));
    }

    private final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
    private final long totalRows;
    private final int batchSize;
    private volatile Schema schema;
    private final Map<Long, Integer> pendingFailures = new ConcurrentHashMap<>();
    private final List<String> executedSql = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, AtomicInteger> chunkExecutionCounts = new ConcurrentHashMap<>();
    private final AtomicInteger activeChunkQueries = new AtomicInteger();
    private final AtomicInteger maxObservedConcurrentChunkQueries = new AtomicInteger();
    private volatile boolean simulateLatency = false;
    private final Map<String, Long> totalRowsByMarker = new ConcurrentHashMap<>();

    public ChunkAwareDremioSource(long totalRows, int batchSize) {
        this(totalRows, batchSize, decimalSchema(18, 3));
    }

    public ChunkAwareDremioSource(long totalRows, int batchSize, Schema schema) {
        this.totalRows = totalRows;
        this.batchSize = batchSize;
        this.schema = schema;
    }

    /** Changes the schema returned by every subsequent query - simulates the source's schema drifting between attempts. */
    public void setSchema(Schema schema) {
        this.schema = schema;
    }

    /**
     * Overrides the total-row-count basis for any query whose SQL text contains {@code marker} -
     * lets one shared source instance (the same wiring as a single production {@code DremioSource}
     * serving every dataset) act as a different-sized dataset per query, by giving each dataset's
     * {@code source-sql} distinguishable content. Falls back to the constructor's {@code totalRows}
     * for any query matching no configured marker.
     */
    public void setTotalRowsForMarker(String marker, long totalRows) {
        totalRowsByMarker.put(marker, totalRows);
    }

    private long resolveTotalRows(String sql) {
        for (Map.Entry<String, Long> entry : totalRowsByMarker.entrySet()) {
            if (sql.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return totalRows;
    }

    /**
     * Arms a one-shot simulated crash for the chunk whose range starts at {@code chunkStart}: the
     * stream throws a retryable {@link DremioSourceException} after successfully emitting
     * {@code afterBatches} batches (0 = fail before emitting anything for this chunk).
     */
    public void failChunkStartingAt(long chunkStart, int afterBatches) {
        pendingFailures.put(chunkStart, afterBatches);
    }

    public List<String> executedSql() {
        synchronized (executedSql) {
            return List.copyOf(executedSql);
        }
    }

    public int executionCountForChunkStart(long chunkStart) {
        AtomicInteger count = chunkExecutionCounts.get(chunkStart);
        return count == null ? 0 : count.get();
    }

    /** Highest number of chunk queries this source ever had simultaneously in flight - used to prove a concurrency bound was actually respected, not just assumed. */
    public int maxObservedConcurrentChunkQueries() {
        return maxObservedConcurrentChunkQueries.get();
    }

    /** Adds a small artificial delay per batch so a real concurrency-bound violation would actually be observed by {@link #maxObservedConcurrentChunkQueries()} instead of racing past unnoticed. */
    public void simulateLatency(boolean simulateLatency) {
        this.simulateLatency = simulateLatency;
    }

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        executedSql.add(sql);
        if (sql.contains("__cache_min")) {
            return probeStream(sql);
        }
        Matcher matcher = RANGE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            throw new IllegalArgumentException("ChunkAwareDremioSource cannot parse chunk bounds from SQL: " + sql);
        }
        long start = Long.parseLong(matcher.group(1));
        long end = Long.parseLong(matcher.group(2));
        chunkExecutionCounts.computeIfAbsent(start, k -> new AtomicInteger()).incrementAndGet();
        Integer failAfterBatches = pendingFailures.remove(start); // one-shot: consumed whether or not it fires
        int nowActive = activeChunkQueries.incrementAndGet();
        maxObservedConcurrentChunkQueries.updateAndGet(prev -> Math.max(prev, nowActive));
        long effectiveTotalRows = resolveTotalRows(sql);
        return new RangeArrowBatchStream(allocator.newChildAllocator("chunk-query", 0, Long.MAX_VALUE),
                schema, Math.max(start, 1), Math.min(end, effectiveTotalRows), batchSize, failAfterBatches,
                activeChunkQueries, simulateLatency);
    }

    private ArrowBatchStream probeStream(String sql) {
        return new ProbeStream(allocator.newChildAllocator("probe-query", 0, Long.MAX_VALUE), resolveTotalRows(sql));
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public void close() {
        allocator.close();
    }

    /** Single-row {@code (min, max)} probe result, both columns generic BIGINT so {@code getObject} yields a {@link Long}. */
    private static final class ProbeStream implements ArrowBatchStream {

        private static final Schema PROBE_SCHEMA = new Schema(List.of(
                new Field("__cache_min", FieldType.nullable(new ArrowType.Int(64, true)), null),
                new Field("__cache_max", FieldType.nullable(new ArrowType.Int(64, true)), null)));

        private final BufferAllocator allocator;
        private final long totalRows;
        private VectorSchemaRoot current;
        private boolean emitted;

        ProbeStream(BufferAllocator allocator, long totalRows) {
            this.allocator = allocator;
            this.totalRows = totalRows;
        }

        @Override
        public Schema schema() {
            return PROBE_SCHEMA;
        }

        @Override
        public boolean loadNextBatch() {
            if (emitted || totalRows <= 0) {
                return false;
            }
            current = VectorSchemaRoot.create(PROBE_SCHEMA, allocator);
            current.allocateNew();
            ((BigIntVector) current.getVector("__cache_min")).setSafe(0, 1L);
            ((BigIntVector) current.getVector("__cache_max")).setSafe(0, totalRows);
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
            allocator.close();
        }
    }

    private static final class RangeArrowBatchStream implements ArrowBatchStream {

        private final BufferAllocator allocator;
        private final Schema schema;
        private final long start;
        private final long end;
        private final int batchSize;
        private final Integer failAfterBatches;
        private final AtomicInteger activeChunkQueries;
        private final boolean simulateLatency;
        private long nextId;
        private int batchesEmitted;
        private VectorSchemaRoot current;
        private boolean closed;

        RangeArrowBatchStream(BufferAllocator allocator, Schema schema, long start, long end, int batchSize,
                Integer failAfterBatches, AtomicInteger activeChunkQueries, boolean simulateLatency) {
            this.allocator = allocator;
            this.schema = schema;
            this.start = start;
            this.end = end;
            this.batchSize = batchSize;
            this.failAfterBatches = failAfterBatches;
            this.activeChunkQueries = activeChunkQueries;
            this.simulateLatency = simulateLatency;
            this.nextId = start;
        }

        @Override
        public Schema schema() {
            return schema;
        }

        @Override
        public boolean loadNextBatch() {
            if (current != null) {
                current.close();
                current = null;
            }
            if (failAfterBatches != null && batchesEmitted >= failAfterBatches) {
                throw new DremioSourceException(
                        "simulated crash mid-chunk after " + batchesEmitted + " batch(es)", true);
            }
            if (nextId > end) {
                return false;
            }
            if (simulateLatency) {
                try {
                    Thread.sleep(5); // widens the window so a real concurrency-bound violation would actually be observed
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int rowsInBatch = (int) Math.min(batchSize, end - nextId + 1);
            current = VectorSchemaRoot.create(schema, allocator);
            current.allocateNew();
            IntVector id = (IntVector) current.getVector("id");
            DecimalVector amount = (DecimalVector) current.getVector("amount");
            int scale = ((ArrowType.Decimal) amount.getField().getType()).getScale();
            for (int i = 0; i < rowsInBatch; i++) {
                long rowId = nextId + i;
                id.setSafe(i, (int) rowId);
                amount.setSafe(i, BigDecimal.valueOf(rowId).setScale(scale, RoundingMode.HALF_UP));
            }
            current.setRowCount(rowsInBatch);
            nextId += rowsInBatch;
            batchesEmitted++;
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
            if (!closed) {
                closed = true;
                activeChunkQueries.decrementAndGet();
            }
        }
    }
}

package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunks a stable, monotonic numeric key (e.g. an increasing transaction ID) into contiguous
 * inclusive ranges of {@code chunk-size} values each. Bounds come only from a single {@code MIN}/
 * {@code MAX} probe against the dataset's own source SQL - never from user input - and are
 * formatted through {@link Long#toString} before being inlined into generated SQL, so there is no
 * string-concatenation injection surface.
 */
public class RangePartitionStrategy implements ResumePartitionStrategy {

    @Override
    public List<ChunkDefinition> planChunks(DremioSource source, String baseSourceSql, DatasetResumeProperties resumeConfig) {
        String column = resumeConfig.getPartitionColumn();
        String probeSql = "SELECT MIN(" + column + ") AS __cache_min, MAX(" + column + ") AS __cache_max "
                + "FROM (" + baseSourceSql + ") __cache_probe";
        Object[] row = SourceProbeReader.readSingleRow(source, probeSql, 2);
        if (row == null || row[0] == null || row[1] == null) {
            return List.of(); // empty source - nothing to chunk
        }

        long min = toLong(row[0], column);
        long max = toLong(row[1], column);
        if (min > max) {
            throw new DremioSourceException("Resume RANGE probe for partition-column '" + column
                    + "' returned MIN(" + min + ") > MAX(" + max + ") - cannot plan chunks", false);
        }

        long chunkSize = resumeConfig.getChunkSize();
        List<ChunkDefinition> chunks = new ArrayList<>();
        long chunkId = 1;
        for (long start = min; start <= max; start += chunkSize) {
            long end = Math.min(start + chunkSize - 1, max);
            chunks.add(new ChunkDefinition(chunkId++, Long.toString(start), Long.toString(end)));
            if (end == Long.MAX_VALUE) {
                break; // guard against overflow on a pathological max value
            }
        }
        return chunks;
    }

    @Override
    public String buildChunkSql(String baseSourceSql, ChunkDefinition chunk, DatasetResumeProperties resumeConfig) {
        String column = resumeConfig.getPartitionColumn();
        // Round-tripping through Long.parseLong guarantees these are pure digits (optionally
        // signed) regardless of how the ChunkDefinition was constructed - defense in depth.
        long start = Long.parseLong(chunk.partitionStart());
        long end = Long.parseLong(chunk.partitionEnd());
        return "SELECT * FROM (" + baseSourceSql + ") __cache_chunk_src "
                + "WHERE __cache_chunk_src." + column + " >= " + start
                + " AND __cache_chunk_src." + column + " <= " + end;
    }

    private static long toLong(Object value, String column) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new DremioSourceException("Resume RANGE partition-column '" + column
                    + "' probe value '" + value + "' (" + value.getClass().getSimpleName()
                    + ") is not numeric - RANGE requires a numeric, monotonic key", false, e);
        }
    }
}

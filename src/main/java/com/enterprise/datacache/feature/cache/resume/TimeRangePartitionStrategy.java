package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunks a date/timestamp-partitioned dataset into one chunk per {@code interval} (e.g. one day),
 * from the source's earliest to latest value of {@code partition-column}, both obtained from a
 * single {@code MIN}/{@code MAX} probe. Bounds are always framework-computed {@link Instant}
 * values formatted through a fixed {@link DateTimeFormatter} - never string-concatenated text.
 */
public class TimeRangePartitionStrategy implements ResumePartitionStrategy {

    private static final DateTimeFormatter LITERAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @Override
    public List<ChunkDefinition> planChunks(DremioSource source, String baseSourceSql, DatasetResumeProperties resumeConfig) {
        String column = resumeConfig.getPartitionColumn();
        String probeSql = "SELECT MIN(" + column + ") AS __cache_min, MAX(" + column + ") AS __cache_max "
                + "FROM (" + baseSourceSql + ") __cache_probe";
        Object[] row = SourceProbeReader.readSingleRow(source, probeSql, 2);
        if (row == null || row[0] == null || row[1] == null) {
            return List.of();
        }

        Instant min = toInstant(row[0], column);
        Instant max = toInstant(row[1], column);
        if (min.isAfter(max)) {
            throw new DremioSourceException("Resume TIME_RANGE probe for partition-column '" + column
                    + "' returned MIN(" + min + ") after MAX(" + max + ") - cannot plan chunks", false);
        }

        long intervalMillis = resumeConfig.getInterval().toMillis();
        List<ChunkDefinition> chunks = new ArrayList<>();
        long chunkId = 1;
        Instant cursor = min;
        while (!cursor.isAfter(max)) {
            Instant next = cursor.plusMillis(intervalMillis);
            Instant chunkEnd = next.isAfter(max) ? max : next.minusMillis(1);
            chunks.add(new ChunkDefinition(chunkId++, LITERAL_FORMAT.format(cursor), LITERAL_FORMAT.format(chunkEnd)));
            cursor = next;
        }
        return chunks;
    }

    @Override
    public String buildChunkSql(String baseSourceSql, ChunkDefinition chunk, DatasetResumeProperties resumeConfig) {
        String column = resumeConfig.getPartitionColumn();
        // Parsing back through the same fixed formatter guarantees these are well-formed
        // timestamps, not arbitrary text, before they are inlined as SQL literals.
        LITERAL_FORMAT.parse(chunk.partitionStart());
        LITERAL_FORMAT.parse(chunk.partitionEnd());
        return "SELECT * FROM (" + baseSourceSql + ") __cache_chunk_src "
                + "WHERE __cache_chunk_src." + column + " >= TIMESTAMP '" + chunk.partitionStart() + "'"
                + " AND __cache_chunk_src." + column + " <= TIMESTAMP '" + chunk.partitionEnd() + "'";
    }

    private static Instant toInstant(Object value, String column) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Number epochDay) {
            return LocalDate.ofEpochDay(epochDay.longValue()).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        throw new DremioSourceException("Resume TIME_RANGE partition-column '" + column
                + "' probe value '" + value + "' (" + (value == null ? "null" : value.getClass().getSimpleName())
                + ") is not a recognized date/timestamp type", false);
    }
}

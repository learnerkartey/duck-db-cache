package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.util.List;

/**
 * Deterministically splits one dataset's source query into durable, independently resumable
 * chunks. A resume chunk is a checkpoint boundary, not an Arrow batch - one chunk typically spans
 * many Arrow batches (see docs/RESUMABLE-REFRESH-AND-RECOVERY.md). Implementations never assume a
 * broken Arrow Flight stream can resume mid-stream: a chunk that fails partway is always re-run
 * from its own start, never from wherever the stream happened to break.
 */
public interface ResumePartitionStrategy {

    /**
     * Computes the full, ordered list of chunks for a brand-new resumable build. May issue one
     * lightweight probe query against {@code source} (e.g. {@code MIN}/{@code MAX}) but must not
     * materialize the dataset's actual rows on this side. Returns an empty list for a genuinely
     * empty source.
     */
    List<ChunkDefinition> planChunks(DremioSource source, String baseSourceSql, DatasetResumeProperties resumeConfig);

    /**
     * Builds the SQL that loads exactly {@code chunk}'s rows from {@code baseSourceSql}. Chunk
     * bounds are always framework-computed typed values (never free text), formatted through
     * Java's own safe numeric/date formatters - never string-concatenated from untrusted input.
     * {@code baseSourceSql} has already had any {@code :snapshotParameter} substituted with a
     * safely-formatted literal by the caller, so every chunk (original and resumed) reads the same
     * bound value.
     */
    String buildChunkSql(String baseSourceSql, ChunkDefinition chunk, DatasetResumeProperties resumeConfig);
}

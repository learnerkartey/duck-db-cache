package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.config.DatasetResumeProperties;
import com.enterprise.datacache.feature.cache.model.ChunkDefinition;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a dataset with no natural contiguous ordering into a fixed number of deterministic hash
 * buckets, each independently resumable. Chunk count is known upfront ({@code hash-buckets}) - no
 * probe query is needed. The hash/modulo expression itself ({@code hash-expression}) is entirely
 * dataset-owner-supplied, trusted configuration: this framework never invents or guesses
 * Dremio-specific hash function syntax, since that cannot be verified without a live Dremio
 * cluster. Configuration validation rejects an expression containing an unsafe SQL fragment
 * ({@code ;}, {@code --}, block comments), but the expression's correctness/determinism is the
 * dataset owner's responsibility.
 */
public class HashBucketPartitionStrategy implements ResumePartitionStrategy {

    @Override
    public List<ChunkDefinition> planChunks(DremioSource source, String baseSourceSql, DatasetResumeProperties resumeConfig) {
        int buckets = resumeConfig.getHashBuckets();
        List<ChunkDefinition> chunks = new ArrayList<>(buckets);
        for (int bucket = 0; bucket < buckets; bucket++) {
            String value = Integer.toString(bucket);
            chunks.add(new ChunkDefinition(bucket + 1L, value, value));
        }
        return chunks;
    }

    @Override
    public String buildChunkSql(String baseSourceSql, ChunkDefinition chunk, DatasetResumeProperties resumeConfig) {
        // partitionStart == partitionEnd == the bucket number for this strategy; round-tripped
        // through Integer.parseInt so only a plain integer can ever reach the generated SQL.
        int bucket = Integer.parseInt(chunk.partitionStart());
        return "SELECT * FROM (" + baseSourceSql + ") __cache_chunk_src "
                + "WHERE (" + resumeConfig.getHashExpression() + ") = " + bucket;
    }
}

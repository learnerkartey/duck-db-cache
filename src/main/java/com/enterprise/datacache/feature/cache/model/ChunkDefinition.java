package com.enterprise.datacache.feature.cache.model;

/**
 * One planned logical chunk of a resumable dataset load - a durable unit of work, not an Arrow
 * batch. {@code partitionStart}/{@code partitionEnd} are pre-formatted, framework-computed values
 * (a decimal string for {@link ResumeStrategyType#RANGE}, an ISO-8601 date for
 * {@link ResumeStrategyType#TIME_RANGE}, or a bucket number for
 * {@link ResumeStrategyType#HASH_BUCKET}) - never raw text from configuration or user input.
 */
public record ChunkDefinition(long chunkId, String partitionStart, String partitionEnd) {
}

package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.model.ResumeStrategyType;

/** Selects the {@link ResumePartitionStrategy} implementation for a configured {@link ResumeStrategyType}. */
public final class ResumePartitionStrategyFactory {

    private static final RangePartitionStrategy RANGE = new RangePartitionStrategy();
    private static final TimeRangePartitionStrategy TIME_RANGE = new TimeRangePartitionStrategy();
    private static final HashBucketPartitionStrategy HASH_BUCKET = new HashBucketPartitionStrategy();

    private ResumePartitionStrategyFactory() {
    }

    public static ResumePartitionStrategy forType(ResumeStrategyType type) {
        return switch (type) {
            case RANGE -> RANGE;
            case TIME_RANGE -> TIME_RANGE;
            case HASH_BUCKET -> HASH_BUCKET;
        };
    }
}

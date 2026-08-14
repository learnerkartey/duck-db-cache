package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.exception.InvalidConfigurationException;
import com.enterprise.datacache.feature.cache.model.ConsistencyMode;
import com.enterprise.datacache.feature.cache.model.SnapshotMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

/**
 * Eagerly validates {@code data-cache.*} configuration at startup so that structurally broken
 * configuration (bad cron, dangling query-&gt;dataset reference, missing SQL location, invalid
 * storage settings) fails fast with an actionable message instead of surfacing later as a cryptic
 * refresh or query failure.
 */
public final class DataCacheConfigurationValidator {

    /** SQL identifiers only - rejects whitespace, quotes, semicolons, comment sequences, and anything else unsafe to inline into generated SQL. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** Applied to trusted-but-still-checked configuration text (e.g. a hash expression) that is inlined into generated SQL. */
    private static final Pattern UNSAFE_SQL_FRAGMENT = Pattern.compile(";|--|/\\*|\\*/");

    private DataCacheConfigurationValidator() {
    }

    public static void validate(DataCacheProperties properties) {
        List<String> errors = new ArrayList<>();

        validateStorage(properties, errors);
        validateDatasets(properties, errors);
        validateQueries(properties, errors);
        validateResume(properties, errors);

        if (!errors.isEmpty()) {
            throw new InvalidConfigurationException(
                    "Invalid data-cache configuration:\n - " + String.join("\n - ", errors));
        }
    }

    private static void validateStorage(DataCacheProperties properties, List<String> errors) {
        DuckDbProperties duckdb = properties.getDuckdb();
        if (!StringUtils.hasText(duckdb.getBaseDirectory())) {
            errors.add("data-cache.duckdb.base-directory must not be blank");
        }
        if (duckdb.getMaxVersions() < 2) {
            errors.add("data-cache.duckdb.max-versions must be >= 2 (need ACTIVE + PREVIOUS for zero-downtime cutover), was "
                    + duckdb.getMaxVersions());
        }
        if (duckdb.getThreads() < 1) {
            errors.add("data-cache.duckdb.threads must be >= 1, was " + duckdb.getThreads());
        }
        if (properties.getRefresh().getMaxConcurrentDatasets() < 1) {
            errors.add("data-cache.refresh.max-concurrent-datasets must be >= 1, was "
                    + properties.getRefresh().getMaxConcurrentDatasets());
        }
        if (properties.getPagination().getMaxPageSize() < 1
                || properties.getPagination().getDefaultPageSize() < 1
                || properties.getPagination().getDefaultPageSize() > properties.getPagination().getMaxPageSize()) {
            errors.add("data-cache.pagination.default-page-size must be between 1 and max-page-size");
        }
        if (properties.getStartup().getTimeout() == null || properties.getStartup().getTimeout().isNegative()
                || properties.getStartup().getTimeout().isZero()) {
            errors.add("data-cache.startup.timeout must be a positive duration");
        }
    }

    private static void validateDatasets(DataCacheProperties properties, List<String> errors) {
        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            String name = entry.getKey();
            DatasetProperties dataset = entry.getValue();
            if (!dataset.isEnabled()) {
                continue;
            }
            if (!StringUtils.hasText(dataset.getSourceSql())) {
                errors.add("data-cache.datasets." + name + ".source-sql must not be blank");
            }
            if (StringUtils.hasText(dataset.getRefreshCron()) && !CronExpression.isValidExpression(dataset.getRefreshCron())) {
                errors.add("data-cache.datasets." + name + ".refresh-cron '" + dataset.getRefreshCron()
                        + "' is not a valid cron expression");
            }
            RetryProperties retry = dataset.getRetry();
            if (retry.getMaxAttempts() < 1) {
                errors.add("data-cache.datasets." + name + ".retry.max-attempts must be >= 1");
            }
            if (retry.getMultiplier() <= 0) {
                errors.add("data-cache.datasets." + name + ".retry.multiplier must be > 0");
            }
            if (dataset.getValidation().getMinimumRowCount() < 0) {
                errors.add("data-cache.datasets." + name + ".validation.minimum-row-count must be >= 0");
            }
        }
    }

    private static void validateResume(DataCacheProperties properties, List<String> errors) {
        if (properties.getResume().getMaxResumeAge() == null || properties.getResume().getMaxResumeAge().isNegative()
                || properties.getResume().getMaxResumeAge().isZero()) {
            errors.add("data-cache.resume.max-resume-age must be a positive duration");
        }

        for (Map.Entry<String, DatasetProperties> entry : properties.getDatasets().entrySet()) {
            String name = entry.getKey();
            DatasetProperties dataset = entry.getValue();
            if (!dataset.isEnabled()) {
                continue;
            }
            DatasetResumeProperties resume = dataset.getResume();
            if (!resume.isEnabled()) {
                continue;
            }
            String prefix = "data-cache.datasets." + name + ".resume.";

            if (resume.getStrategy() == null) {
                errors.add(prefix + "strategy is required when resume.enabled=true "
                        + "(RANGE, TIME_RANGE, or HASH_BUCKET) - do not silently fall back to unsafe resume");
                continue; // strategy-specific checks below all depend on knowing which one
            }

            switch (resume.getStrategy()) {
                case RANGE -> {
                    requireSafeIdentifier(resume.getPartitionColumn(), prefix + "partition-column", errors);
                    if (resume.getChunkSize() < 1) {
                        errors.add(prefix + "chunk-size must be >= 1");
                    }
                }
                case TIME_RANGE -> {
                    requireSafeIdentifier(resume.getPartitionColumn(), prefix + "partition-column", errors);
                    if (resume.getInterval() == null || resume.getInterval().isNegative() || resume.getInterval().isZero()) {
                        errors.add(prefix + "interval must be a positive duration when strategy=TIME_RANGE");
                    }
                }
                case HASH_BUCKET -> {
                    if (!StringUtils.hasText(resume.getHashExpression())) {
                        errors.add(prefix + "hash-expression is required when strategy=HASH_BUCKET - the framework "
                                + "does not invent Dremio-specific hash syntax, the dataset owner must supply a "
                                + "verified deterministic expression");
                    } else if (UNSAFE_SQL_FRAGMENT.matcher(resume.getHashExpression()).find()) {
                        errors.add(prefix + "hash-expression contains an unsafe SQL fragment (';', '--', '/*', '*/')");
                    }
                    if (resume.getHashBuckets() < 1) {
                        errors.add(prefix + "hash-buckets must be >= 1");
                    }
                }
            }

            if (resume.getConsistency() == ConsistencyMode.STRICT_SNAPSHOT) {
                if (resume.getSnapshot().getMode() != SnapshotMode.AS_OF_VALUE) {
                    errors.add(prefix + "consistency=STRICT_SNAPSHOT (the default) requires resume.snapshot.mode: "
                            + "AS_OF_VALUE with a snapshot.parameter-name your source SQL references - without a "
                            + "snapshot binding, a resumed refresh cannot be proven to read a consistent source "
                            + "state. Configure snapshot.mode: AS_OF_VALUE, or explicitly accept the risk with "
                            + "consistency: BEST_EFFORT.");
                } else if (!StringUtils.hasText(resume.getSnapshot().getParameterName())) {
                    errors.add(prefix + "snapshot.parameter-name is required when snapshot.mode=AS_OF_VALUE");
                }
            }
        }
    }

    private static void requireSafeIdentifier(String value, String propertyPath, List<String> errors) {
        if (!StringUtils.hasText(value)) {
            errors.add(propertyPath + " is required for this resume strategy");
        } else if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            errors.add(propertyPath + " '" + value + "' must be a plain SQL identifier "
                    + "(letters, digits, underscore, not starting with a digit)");
        }
    }

    private static void validateQueries(DataCacheProperties properties, List<String> errors) {
        for (Map.Entry<String, QueryProperties> entry : properties.getQueries().entrySet()) {
            String name = entry.getKey();
            QueryProperties query = entry.getValue();
            if (!StringUtils.hasText(query.getSql())) {
                errors.add("data-cache.queries." + name + ".sql must not be blank");
            }
            if (query.getDatasets() == null || query.getDatasets().isEmpty()) {
                errors.add("data-cache.queries." + name + ".datasets must reference at least one dataset");
                continue;
            }
            for (String datasetName : query.getDatasets()) {
                DatasetProperties referenced = properties.getDatasets().get(datasetName);
                if (referenced == null) {
                    errors.add("data-cache.queries." + name + " references unknown dataset '" + datasetName + "'");
                } else if (!referenced.isEnabled()) {
                    errors.add("data-cache.queries." + name + " references disabled dataset '" + datasetName + "'");
                }
            }
        }
    }
}

package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.exception.InvalidConfigurationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

/**
 * Eagerly validates {@code data-cache.*} configuration at startup so that structurally broken
 * configuration (bad cron, dangling query-&gt;dataset reference, missing SQL location, invalid
 * storage settings) fails fast with an actionable message instead of surfacing later as a cryptic
 * refresh or query failure.
 */
public final class DataCacheConfigurationValidator {

    private DataCacheConfigurationValidator() {
    }

    public static void validate(DataCacheProperties properties) {
        List<String> errors = new ArrayList<>();

        validateStorage(properties, errors);
        validateDatasets(properties, errors);
        validateQueries(properties, errors);

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

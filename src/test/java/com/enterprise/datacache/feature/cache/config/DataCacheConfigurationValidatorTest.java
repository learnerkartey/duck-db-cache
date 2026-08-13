package com.enterprise.datacache.feature.cache.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.datacache.feature.cache.exception.InvalidConfigurationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCacheConfigurationValidatorTest {

    private DataCacheProperties validBaseline() {
        DataCacheProperties properties = new DataCacheProperties();
        DatasetProperties dataset = new DatasetProperties();
        dataset.setSourceSql("classpath:datacache/dremio/financial.sql");
        dataset.setRefreshCron("0 0 1 * * *");
        properties.getDatasets().put("financial", dataset);
        return properties;
    }

    @Test
    void acceptsValidConfiguration() {
        assertThatCode(() -> DataCacheConfigurationValidator.validate(validBaseline())).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSourceSql() {
        DataCacheProperties properties = validBaseline();
        properties.getDatasets().get("financial").setSourceSql(null);
        assertThatThrownBy(() -> DataCacheConfigurationValidator.validate(properties))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("source-sql");
    }

    @Test
    void rejectsInvalidCronExpression() {
        DataCacheProperties properties = validBaseline();
        properties.getDatasets().get("financial").setRefreshCron("not a cron");
        assertThatThrownBy(() -> DataCacheConfigurationValidator.validate(properties))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void rejectsMaxVersionsBelowTwo() {
        DataCacheProperties properties = validBaseline();
        properties.getDuckdb().setMaxVersions(1);
        assertThatThrownBy(() -> DataCacheConfigurationValidator.validate(properties))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("max-versions");
    }

    @Test
    void rejectsQueryReferencingUnknownDataset() {
        DataCacheProperties properties = validBaseline();
        QueryProperties query = new QueryProperties();
        query.setSql("classpath:datacache/query/cfo-summary.sql");
        query.setDatasets(List.of("financial", "does-not-exist"));
        properties.getQueries().put("cfo-summary", query);
        assertThatThrownBy(() -> DataCacheConfigurationValidator.validate(properties))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void rejectsInvalidStorageConfig() {
        DataCacheProperties properties = validBaseline();
        properties.getDuckdb().setBaseDirectory("  ");
        assertThatThrownBy(() -> DataCacheConfigurationValidator.validate(properties))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("base-directory");
    }
}

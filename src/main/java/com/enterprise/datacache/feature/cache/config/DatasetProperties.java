package com.enterprise.datacache.feature.cache.config;

/** Configuration for a single dataset. Adding a new dataset requires only a new entry of this shape plus a source SQL file. */
public class DatasetProperties {

    private boolean enabled = true;

    /** Physical/logical table name created inside the dataset's DuckDB file. Defaults to the map key if unset. */
    private String tableName;

    /** Resource location of the Dremio source SQL, e.g. {@code classpath:datacache/dremio/financial.sql}. */
    private String sourceSql;

    /** Standard 6-field Spring cron expression (with seconds); null/blank disables scheduled refresh. */
    private String refreshCron;

    /** Per-dataset startup behavior - see {@link StartupMode}. */
    private DatasetStartupProperties startup = new DatasetStartupProperties();

    /**
     * Whether this dataset counts toward readiness when
     * {@code data-cache.startup.execution-mode} is {@code BLOCK_UNTIL_REQUIRED_CACHE_READY}.
     * Non-required datasets may still be unavailable without holding back the readiness probe.
     */
    private boolean required = true;

    private RetryProperties retry = new RetryProperties();

    private ValidationProperties validation = new ValidationProperties();

    private DatasetProgressProperties progress = new DatasetProgressProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
    }

    public String getRefreshCron() {
        return refreshCron;
    }

    public void setRefreshCron(String refreshCron) {
        this.refreshCron = refreshCron;
    }

    public DatasetStartupProperties getStartup() {
        return startup;
    }

    public void setStartup(DatasetStartupProperties startup) {
        this.startup = startup;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public RetryProperties getRetry() {
        return retry;
    }

    public void setRetry(RetryProperties retry) {
        this.retry = retry;
    }

    public ValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(ValidationProperties validation) {
        this.validation = validation;
    }

    public DatasetProgressProperties getProgress() {
        return progress;
    }

    public void setProgress(DatasetProgressProperties progress) {
        this.progress = progress;
    }
}

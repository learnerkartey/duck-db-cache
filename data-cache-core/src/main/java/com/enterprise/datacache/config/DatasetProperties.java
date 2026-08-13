package com.enterprise.datacache.config;

/** Configuration for a single dataset. Adding a new dataset requires only a new entry of this shape plus a source SQL file. */
public class DatasetProperties {

    private boolean enabled = true;

    /** Physical/logical table name created inside the dataset's DuckDB file. Defaults to the map key if unset. */
    private String tableName;

    /** Resource location of the Dremio source SQL, e.g. {@code classpath:datacache/dremio/financial.sql}. */
    private String sourceSql;

    /** Standard 6-field Spring cron expression (with seconds); null/blank disables scheduled refresh. */
    private String refreshCron;

    /** Whether to trigger a refresh automatically on application startup (subject to recovery/lock rules). */
    private boolean loadOnStartup = false;

    private RetryProperties retry = new RetryProperties();

    private ValidationProperties validation = new ValidationProperties();

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

    public boolean isLoadOnStartup() {
        return loadOnStartup;
    }

    public void setLoadOnStartup(boolean loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
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
}

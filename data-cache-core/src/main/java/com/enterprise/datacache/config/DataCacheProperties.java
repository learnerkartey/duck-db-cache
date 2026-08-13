package com.enterprise.datacache.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root typed configuration for the data cache, bound from the {@code data-cache.*} namespace.
 * See docs/03-CONFIGURATION-REFERENCE.md for the full property reference.
 */
@ConfigurationProperties(prefix = "data-cache")
public class DataCacheProperties {

    /** Master switch. When false, {@code DataCacheAutoConfiguration} registers no beans. */
    private boolean enabled = true;

    private DremioProperties dremio = new DremioProperties();

    private ArrowProperties arrow = new ArrowProperties();

    private DuckDbProperties duckdb = new DuckDbProperties();

    private RefreshProperties refresh = new RefreshProperties();

    private PaginationProperties pagination = new PaginationProperties();

    /** Dataset name -&gt; configuration. Keys are the stable logical/table names used in analytical SQL. */
    private Map<String, DatasetProperties> datasets = new LinkedHashMap<>();

    /** Query name -&gt; configuration. Keys are the names REST/Java callers pass to execute a query. */
    private Map<String, QueryProperties> queries = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DremioProperties getDremio() {
        return dremio;
    }

    public void setDremio(DremioProperties dremio) {
        this.dremio = dremio;
    }

    public ArrowProperties getArrow() {
        return arrow;
    }

    public void setArrow(ArrowProperties arrow) {
        this.arrow = arrow;
    }

    public DuckDbProperties getDuckdb() {
        return duckdb;
    }

    public void setDuckdb(DuckDbProperties duckdb) {
        this.duckdb = duckdb;
    }

    public RefreshProperties getRefresh() {
        return refresh;
    }

    public void setRefresh(RefreshProperties refresh) {
        this.refresh = refresh;
    }

    public PaginationProperties getPagination() {
        return pagination;
    }

    public void setPagination(PaginationProperties pagination) {
        this.pagination = pagination;
    }

    public Map<String, DatasetProperties> getDatasets() {
        return datasets;
    }

    public void setDatasets(Map<String, DatasetProperties> datasets) {
        this.datasets = datasets;
    }

    public Map<String, QueryProperties> getQueries() {
        return queries;
    }

    public void setQueries(Map<String, QueryProperties> queries) {
        this.queries = queries;
    }
}

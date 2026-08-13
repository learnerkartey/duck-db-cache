package com.enterprise.datacache.config;

import java.util.ArrayList;
import java.util.List;

/** Configuration for a single registered analytical query. Adding a query requires only a SQL file plus one of these entries. */
public class QueryProperties {

    /** Resource location of the analytical SQL, e.g. {@code classpath:datacache/query/cfo-summary.sql}. Runs in DuckDB only. */
    private String sql;

    /** Logical dataset names (stable table names) this query reads from; their ACTIVE versions are pinned/attached. */
    private List<String> datasets = new ArrayList<>();

    /** Optional override of the global max page size for this query. */
    private Integer maxPageSize;

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<String> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<String> datasets) {
        this.datasets = datasets;
    }

    public Integer getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(Integer maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}

package com.enterprise.datacache.config;

/** Global pagination guardrails applied to every registered query. */
public class PaginationProperties {

    private int defaultPageSize = 100;

    private int maxPageSize = 5000;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}

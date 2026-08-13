package com.enterprise.datacache.config;

import java.util.ArrayList;
import java.util.List;

/** Validation rules a newly BUILT version must satisfy before it can become ACTIVE. */
public class ValidationProperties {

    /** Minimum acceptable row count; 0 disables the check. */
    private long minimumRowCount = 0;

    /** Column names (case-insensitive) that must be present in the loaded schema. */
    private List<String> requiredColumns = new ArrayList<>();

    /**
     * Optional resource location (e.g. {@code classpath:datacache/validation/financial.sql}) of a
     * read-only SQL check executed against the BUILDING version. The query must return exactly one
     * row with one boolean-ish column; a falsy/zero result fails validation.
     */
    private String sql;

    public long getMinimumRowCount() {
        return minimumRowCount;
    }

    public void setMinimumRowCount(long minimumRowCount) {
        this.minimumRowCount = minimumRowCount;
    }

    public List<String> getRequiredColumns() {
        return requiredColumns;
    }

    public void setRequiredColumns(List<String> requiredColumns) {
        this.requiredColumns = requiredColumns;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }
}

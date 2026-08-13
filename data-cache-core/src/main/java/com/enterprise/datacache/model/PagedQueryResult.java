package com.enterprise.datacache.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic, paginated result of executing a registered analytical query against the DuckDB cache.
 * Pagination (LIMIT/OFFSET) is pushed down into DuckDB - rows are never materialized beyond the
 * requested page size.
 */
public record PagedQueryResult(
        String queryName,
        List<QueryColumn> columns,
        List<Map<String, Object>> rows,
        int page,
        int size,
        long totalRows,
        boolean hasNext,
        long executionTimeMs,
        Map<String, Long> datasetVersionsUsed) {

    /** Incremental builder used while streaming a single page's rows out of a JDBC {@code ResultSet}. */
    public static final class Builder {
        private final String queryName;
        private final int page;
        private final int size;
        private List<QueryColumn> columns = List.of();
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private long totalRows;
        private boolean hasNext;

        public Builder(String queryName, int page, int size) {
            this.queryName = queryName;
            this.page = page;
            this.size = size;
        }

        public Builder columns(List<QueryColumn> columns) {
            this.columns = columns;
            return this;
        }

        public Builder addRow(Map<String, Object> row) {
            this.rows.add(row);
            return this;
        }

        public Builder totalRows(long totalRows) {
            this.totalRows = totalRows;
            return this;
        }

        public Builder hasNext(boolean hasNext) {
            this.hasNext = hasNext;
            return this;
        }

        public PagedQueryResult build(Map<String, Long> datasetVersionsUsed, long executionTimeMs) {
            return new PagedQueryResult(queryName, columns, rows, page, size, totalRows, hasNext, executionTimeMs,
                    datasetVersionsUsed);
        }
    }
}

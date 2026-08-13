package com.enterprise.datacache.arrow;

/**
 * The result of mapping one Arrow {@code Field} to DuckDB: the DDL type used in {@code CREATE TABLE}
 * and the strategy that copies each row's value from the Arrow vector into the DuckDB appender.
 */
public record ColumnMapping(String columnName, String duckDbType, ColumnValueAppender appender) {
}

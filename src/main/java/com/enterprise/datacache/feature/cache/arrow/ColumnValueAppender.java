package com.enterprise.datacache.feature.cache.arrow;

import java.sql.SQLException;
import org.apache.arrow.vector.FieldVector;
import org.duckdb.DuckDBAppender;

/** Copies a single row's value from an Arrow {@link FieldVector} into an open {@link DuckDBAppender} column slot. */
@FunctionalInterface
public interface ColumnValueAppender {

    void append(DuckDBAppender appender, FieldVector vector, int rowIndex) throws SQLException;
}

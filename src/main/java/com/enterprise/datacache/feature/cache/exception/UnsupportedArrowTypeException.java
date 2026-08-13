package com.enterprise.datacache.feature.cache.exception;

/** Raised when an Arrow column type has no supported DuckDB mapping. Never retryable - the source SQL must change. */
public class UnsupportedArrowTypeException extends DataCacheException {

    public UnsupportedArrowTypeException(String columnName, String arrowType) {
        super("UNSUPPORTED_ARROW_TYPE",
                "Column '" + columnName + "' has Arrow type '" + arrowType
                        + "' which has no supported DuckDB mapping. Adjust the source SQL to cast this column.",
                false);
    }
}

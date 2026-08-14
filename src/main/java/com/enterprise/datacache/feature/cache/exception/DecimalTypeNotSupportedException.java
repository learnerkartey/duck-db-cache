package com.enterprise.datacache.feature.cache.exception;

/**
 * Raised when a source Arrow {@code DECIMAL(precision,scale)} cannot be represented exactly by
 * DuckDB. Per the decimal schema evolution policy, the source type is always authoritative: the
 * BUILDING version's schema is derived fresh from the current Arrow schema on every refresh, never
 * from a previous version's DuckDB file. When that source decimal definition cannot be represented
 * without narrowing precision or scale, rounding, truncating, or passing through {@code float}/
 * {@code double}, the new version load fails outright rather than silently degrading - the current
 * ACTIVE version (built from a schema that DID fit) is left completely untouched. Never retryable:
 * the source schema or dataset configuration must change.
 */
public class DecimalTypeNotSupportedException extends DataCacheException {

    public DecimalTypeNotSupportedException(String datasetName, String columnName, int sourcePrecision,
            int sourceScale, String requestedDuckDbType, String reason) {
        super("DECIMAL_TYPE_NOT_SUPPORTED", "dataset='" + datasetName + "' column='" + columnName
                + "' sourcePrecision=" + sourcePrecision + " sourceScale=" + sourceScale
                + " requestedDuckDbType='" + requestedDuckDbType + "': " + reason
                + ". Refusing to silently narrow precision/scale, round, truncate, or convert through "
                + "float/double - the new version has been failed and the current ACTIVE version is untouched.",
                false);
    }
}

package com.enterprise.datacache.feature.cache.exception;

/** Raised when a registered query references a named parameter that the caller did not supply. */
public class MissingQueryParameterException extends DataCacheException {

    public MissingQueryParameterException(String queryName, String parameterName) {
        super("MISSING_QUERY_PARAMETER",
                "Query '" + queryName + "' requires parameter '" + parameterName + "' which was not supplied", false);
    }
}

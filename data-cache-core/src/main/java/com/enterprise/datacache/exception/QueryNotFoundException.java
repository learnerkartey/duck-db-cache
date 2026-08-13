package com.enterprise.datacache.exception;

/** Raised when a caller references a query name that is not registered under {@code data-cache.queries}. */
public class QueryNotFoundException extends DataCacheException {

    public QueryNotFoundException(String queryName) {
        super("QUERY_NOT_FOUND", "No query registered with name '" + queryName + "'", false);
    }
}

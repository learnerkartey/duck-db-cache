package com.enterprise.datacache.query;

import com.enterprise.datacache.model.PagedQueryResult;
import java.util.Map;

/**
 * Executes a registered analytical query (see {@code data-cache.queries.*}) against the DuckDB
 * cache. This is the primary integration point for another Spring Boot application embedding
 * {@code data-cache-core}: inject this interface and call {@link #execute} with the query name
 * registered in YAML, named parameter values, and a page/size.
 *
 * <p>Only query names pre-registered in configuration may be executed - there is no way to submit
 * arbitrary SQL through this interface.
 */
public interface DataCacheQueryService {

    /**
     * @param queryName name of a query registered under {@code data-cache.queries}
     * @param parameters named parameter values referenced as {@code :name} in the query SQL
     * @param page zero-based page index
     * @param size requested page size; capped at the query's (or global) configured maximum
     */
    PagedQueryResult execute(String queryName, Map<String, Object> parameters, int page, int size);
}

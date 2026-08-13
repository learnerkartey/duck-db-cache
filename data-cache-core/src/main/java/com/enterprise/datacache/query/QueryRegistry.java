package com.enterprise.datacache.query;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.PaginationProperties;
import com.enterprise.datacache.config.QueryProperties;
import com.enterprise.datacache.exception.QueryNotFoundException;
import com.enterprise.datacache.util.SqlResourceLoader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code data-cache.queries.*} configuration into ready-to-execute {@link QueryDefinition}s
 * at startup. Adding a new analytical query requires only a new SQL file plus a new entry here in
 * YAML - no Java class of any kind.
 */
public class QueryRegistry {

    private final Map<String, QueryDefinition> definitions = new LinkedHashMap<>();

    public QueryRegistry(DataCacheProperties properties, SqlResourceLoader sqlResourceLoader) {
        PaginationProperties pagination = properties.getPagination();
        for (Map.Entry<String, QueryProperties> entry : properties.getQueries().entrySet()) {
            String name = entry.getKey();
            QueryProperties config = entry.getValue();
            String sqlText = sqlResourceLoader.load(config.getSql());
            NamedParameterSqlBinder.BoundSql boundSql = NamedParameterSqlBinder.parse(sqlText);
            int maxPageSize = config.getMaxPageSize() != null ? config.getMaxPageSize() : pagination.getMaxPageSize();
            definitions.put(name, new QueryDefinition(name, boundSql, config.getDatasets(), maxPageSize));
        }
    }

    public QueryDefinition get(String queryName) {
        QueryDefinition definition = definitions.get(queryName);
        if (definition == null) {
            throw new QueryNotFoundException(queryName);
        }
        return definition;
    }

    public Map<String, QueryDefinition> all() {
        return Map.copyOf(definitions);
    }
}

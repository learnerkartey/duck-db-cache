package com.enterprise.datacache.feature.cache.query;

import java.util.List;

/** A fully resolved, ready-to-execute registered analytical query. */
public record QueryDefinition(
        String name,
        NamedParameterSqlBinder.BoundSql boundSql,
        List<String> datasets,
        int maxPageSize) {
}

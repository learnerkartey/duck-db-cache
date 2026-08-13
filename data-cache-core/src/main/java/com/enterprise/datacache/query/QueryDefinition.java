package com.enterprise.datacache.query;

import java.util.List;

/** A fully resolved, ready-to-execute registered analytical query. */
public record QueryDefinition(
        String name,
        NamedParameterSqlBinder.BoundSql boundSql,
        List<String> datasets,
        int maxPageSize) {
}

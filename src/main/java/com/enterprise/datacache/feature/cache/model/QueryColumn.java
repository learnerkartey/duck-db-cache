package com.enterprise.datacache.feature.cache.model;

/** Describes a single column of a {@link PagedQueryResult}. */
public record QueryColumn(String name, String sqlType) {
}

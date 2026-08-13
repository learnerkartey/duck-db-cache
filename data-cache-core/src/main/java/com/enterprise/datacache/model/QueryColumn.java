package com.enterprise.datacache.model;

/** Describes a single column of a {@link PagedQueryResult}. */
public record QueryColumn(String name, String sqlType) {
}

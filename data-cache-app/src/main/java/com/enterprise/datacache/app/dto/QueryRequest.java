package com.enterprise.datacache.app.dto;

import java.util.Map;

/** Request body for {@code POST /api/v1/cache/query/{queryName}}. */
public record QueryRequest(Map<String, Object> parameters, Integer page, Integer size) {

    public Map<String, Object> parametersOrEmpty() {
        return parameters == null ? Map.of() : parameters;
    }

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault(int defaultSize) {
        return size == null ? defaultSize : size;
    }
}

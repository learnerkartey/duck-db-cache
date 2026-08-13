package com.enterprise.datacache.app.controller;

import com.enterprise.datacache.app.dto.QueryRequest;
import com.enterprise.datacache.model.PagedQueryResult;
import com.enterprise.datacache.query.DataCacheQueryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executes registered analytical queries. There is deliberately no arbitrary-SQL endpoint -
 * clients may only invoke query names pre-registered under {@code data-cache.queries}.
 */
@RestController
@RequestMapping("/api/v1/cache/query")
public class QueryController {

    private final DataCacheQueryService queryService;

    public QueryController(DataCacheQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/{queryName}")
    public PagedQueryResult execute(@PathVariable String queryName, @RequestBody(required = false) QueryRequest request) {
        QueryRequest effective = request == null ? new QueryRequest(null, null, null) : request;
        return queryService.execute(queryName, effective.parametersOrEmpty(), effective.pageOrDefault(),
                effective.sizeOrDefault(0));
    }
}

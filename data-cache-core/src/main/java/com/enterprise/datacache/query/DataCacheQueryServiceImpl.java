package com.enterprise.datacache.query;

import com.enterprise.datacache.metrics.DataCacheMetrics;
import com.enterprise.datacache.model.PagedQueryResult;
import io.micrometer.core.instrument.Timer;
import java.util.Map;

public class DataCacheQueryServiceImpl implements DataCacheQueryService {

    private final DuckDbQueryEngine queryEngine;
    private final DataCacheMetrics metrics;

    public DataCacheQueryServiceImpl(DuckDbQueryEngine queryEngine, DataCacheMetrics metrics) {
        this.queryEngine = queryEngine;
        this.metrics = metrics;
    }

    @Override
    public PagedQueryResult execute(String queryName, Map<String, Object> parameters, int page, int size) {
        Timer.Sample sample = metrics.startQueryTimer();
        try {
            PagedQueryResult result = queryEngine.execute(queryName, parameters, page, size);
            metrics.recordQuerySuccess(queryName, sample);
            return result;
        } catch (RuntimeException e) {
            metrics.recordQueryFailure(queryName, sample);
            throw e;
        }
    }
}

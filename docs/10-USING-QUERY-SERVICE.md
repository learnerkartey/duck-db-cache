# 10. Using the Query Service (Java API)

```java
@Service
public class FinanceService {

    private final DataCacheQueryService queryService;

    public FinanceService(DataCacheQueryService queryService) {
        this.queryService = queryService;
    }

    public PagedQueryResult getCfoSummary(int fiscalYear) {
        return queryService.execute(
                "cfo-summary",
                Map.of("fiscalYear", fiscalYear),
                0,
                100);
    }
}
```

`DataCacheQueryService` is an ordinary Spring bean - inject it into any `@Service`, `@Component`,
or controller. No other wiring is required beyond `@EnableDataCache` being present somewhere in
the application and `data-cache.queries.cfo-summary` registered in configuration.

## `PagedQueryResult`

```java
public record PagedQueryResult(
        String queryName,
        List<QueryColumn> columns,
        List<Map<String, Object>> rows,
        int page,
        int size,
        long totalRows,
        boolean hasNext,
        long executionTimeMs,
        Map<String, Long> datasetVersionsUsed) {}
```

- `rows` never contains more than `size` entries - pagination is enforced by DuckDB `LIMIT/OFFSET`,
  not by slicing a larger in-memory list.
- `datasetVersionsUsed` reports exactly which ACTIVE version of each dataset answered this call -
  useful for auditing/debugging ("why did this number change between two calls a second apart?").
- `totalRows`/`hasNext` are always accurate, computed via a single `COUNT(*) OVER()` window
  function alongside the page fetch (no second full scan).

## Pagination best practices

- Never call with `size` larger than necessary - the configured `max-page-size` caps it
  regardless, but a smaller page is cheaper to serialize.
- Design analytical SQL to aggregate/filter before pagination matters (see
  [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md#performance-best-practices)) - a
  `GROUP BY` that reduces 30M rows to 500 makes pagination almost free; paginating a 30M-row raw
  scan is not what this service is for.
- Iterate pages with `page = 0, 1, 2, ...` until `hasNext == false`; do not assume `totalRows`
  stays constant across calls if the dataset refreshes between them (rare, but possible for a
  long-running export).

## Error handling

`DataCacheQueryService.execute` throws unchecked `DataCacheException` subtypes:

| Exception | Cause |
|---|---|
| `QueryNotFoundException` | Unregistered query name |
| `DatasetNotFoundException` | A pinned dataset has no ACTIVE version yet |
| `MissingQueryParameterException` | The SQL references `:name` but the caller didn't supply it |
| `DuckDbWriteException` | DuckDB execution failure (surfaced from the JDBC driver) |

Every one exposes `getErrorCode()` for programmatic handling.

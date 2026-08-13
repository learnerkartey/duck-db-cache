# 13. Adding a New Query

Worked example: adding `actual-forecast-by-cio`. **No Java repository, entity, DAO, query
service, or controller is required.**

## 1. Create the SQL

`data-cache-app/src/main/resources/datacache/query/actual-forecast-by-cio.sql`:

```sql
SELECT
    o.cio,
    SUM(f.actual_amount) AS actual,
    SUM(f.forecast_amount) AS forecast,
    SUM(f.actual_amount - f.forecast_amount) AS variance
FROM financial f
JOIN organization o ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY o.cio
ORDER BY o.cio
```

## 2. Register it

```yaml
data-cache:
  queries:
    actual-forecast-by-cio:
      sql: classpath:datacache/query/actual-forecast-by-cio.sql
      datasets:
        - financial
        - organization
```

## 3. Call it from Java

```java
PagedQueryResult result = queryService.execute(
        "actual-forecast-by-cio", Map.of("fiscalYear", 2026), 0, 100);
```

## 4. Call it from REST

```bash
curl -X POST localhost:8080/api/v1/cache/query/actual-forecast-by-cio \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"fiscalYear":2026}}'
```

That's the entire process - restart the application (query registration happens at startup via
`QueryRegistry`) and the new query is live.

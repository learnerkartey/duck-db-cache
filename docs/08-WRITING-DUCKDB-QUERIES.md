# 08. Writing DuckDB Queries

Analytical query SQL (`data-cache.queries.<name>.sql`) runs **entirely inside DuckDB**, against
the cached data. It never calls Dremio. Register it once in YAML
([09-QUERY-CONFIGURATION.md](09-QUERY-CONFIGURATION.md)) and it becomes callable from Java
([10-USING-QUERY-SERVICE.md](10-USING-QUERY-SERVICE.md)) and REST
([11-REST-API.md](11-REST-API.md)).

Every dataset listed in a query's `datasets:` is exposed under its **stable logical name** - the
dataset's YAML key - regardless of which physical version is currently ACTIVE. Write
`FROM financial`, never `FROM financial_v11` and never a file path.

## Single dataset query

```sql
SELECT
    fiscal_year,
    fiscal_month,
    SUM(actual_amount) AS actual,
    SUM(forecast_amount) AS forecast
FROM financial
WHERE fiscal_year = :fiscalYear
GROUP BY
    fiscal_year,
    fiscal_month
ORDER BY
    fiscal_month
```

Register with `datasets: [financial]`. Java:

```java
queryService.execute("financial-summary", Map.of("fiscalYear", 2026), 0, 100);
```

REST:

```bash
curl -X POST localhost:8080/api/v1/cache/query/financial-summary \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"fiscalYear":2026},"page":0,"size":100}'
```

## Two-dataset JOIN

```sql
SELECT
    o.cio,
    o.business_unit,
    SUM(f.actual_amount) AS actual,
    SUM(f.forecast_amount) AS forecast,
    SUM(f.actual_amount - f.forecast_amount) AS variance
FROM financial f
JOIN organization o
    ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY
    o.cio,
    o.business_unit
```

Register with `datasets: [financial, organization]`. Before execution, the query engine pins each
dataset's currently ACTIVE version, `ATTACH`es both physical files under private aliases in one
isolated DuckDB connection, and creates `financial`/`organization` as views over them - the join
above runs unmodified. This entire join, aggregation, and grouping happens inside DuckDB; only the
final (paginated) result rows cross into Java.

## Three-dataset JOIN

```sql
SELECT
    o.cio,
    o.department,
    h.fiscal_year,
    SUM(h.headcount_count) AS total_headcount,
    SUM(f.actual_amount) AS total_cost
FROM headcount h
JOIN organization o ON h.cost_center = o.cost_center
JOIN financial f     ON f.cost_center = o.cost_center AND f.fiscal_year = h.fiscal_year
WHERE h.fiscal_year = :fiscalYear
GROUP BY o.cio, o.department, h.fiscal_year
```

**Join-grain warning**: `financial` and `headcount` both have a `(cost_center, fiscal_year)` grain
in the example schemas, but if either has finer or coarser grain than you expect (e.g. `financial`
also has a `fiscal_month` dimension not joined on here), a three-way join can silently multiply
rows (a many-to-many "fan-out"). Always confirm each table's grain before joining, and consider
pre-aggregating one side in a CTE first (see below).

## GROUP BY / HAVING

```sql
SELECT cost_center, SUM(actual_amount) AS total
FROM financial
WHERE fiscal_year = :fiscalYear
GROUP BY cost_center
HAVING SUM(actual_amount) > :threshold
```

## CTE

```sql
WITH monthly AS (
    SELECT fiscal_year, fiscal_month, SUM(actual_amount) AS actual
    FROM financial
    WHERE fiscal_year = :fiscalYear
    GROUP BY fiscal_year, fiscal_month
)
SELECT fiscal_month, actual, SUM(actual) OVER (ORDER BY fiscal_month) AS running_total
FROM monthly
ORDER BY fiscal_month
```

## Window functions

```sql
SELECT
    cost_center,
    fiscal_month,
    actual_amount,
    RANK() OVER (PARTITION BY fiscal_month ORDER BY actual_amount DESC) AS rank_in_month,
    LAG(actual_amount) OVER (PARTITION BY cost_center ORDER BY fiscal_month) AS prior_month
FROM financial
WHERE fiscal_year = :fiscalYear
```

Standard DuckDB read-only analytical SQL is fully supported - `JOIN`/`LEFT JOIN`, `GROUP BY`,
`ORDER BY`, `HAVING`, CTEs, window functions, `SUM`/`COUNT`/`AVG`/`MIN`/`MAX`, filters. There is no
artificial SQL subset restriction.

## Parameters

Named parameters (`:name`) are parsed by `NamedParameterSqlBinder` and bound through
`PreparedStatement`, never string concatenation:

```sql
WHERE f.fiscal_year = :fiscalYear AND o.cio = :cio
```

```java
Map.of("fiscalYear", 2026, "cio", "Technology")
```

- The same name may repeat (`:fiscalYear` used twice) - each occurrence binds independently to the
  same value.
- A `:name` inside a string literal, quoted identifier, or SQL comment is never treated as a
  parameter (the binder tracks quoting/comment state character by character).
- A missing parameter raises `MissingQueryParameterException` (HTTP 400) rather than binding
  `null` silently.
- Values containing quotes, `;`, `--`, or `/* */` are always treated as literal data - see
  `PaginationAndParameterSafetyTest.namedParametersContainingSqlSyntaxAreTreatedAsLiteralDataNotSql`
  for a real test with several injection-shaped strings.

## Dates and decimals

Pass `java.time.LocalDate`/`LocalDateTime` and `java.math.BigDecimal` directly as parameter
values - the DuckDB JDBC driver binds them natively; no string formatting needed.

## Pagination

Every query is paginated: `page`/`size` map to `LIMIT`/`OFFSET` pushed into DuckDB, alongside a
single `COUNT(*) OVER()` window function that computes the total row count in the *same* query
execution (no second full scan). Never write your own `LIMIT`/`OFFSET` in the registered SQL - the
engine appends it. See [10-USING-QUERY-SERVICE.md](10-USING-QUERY-SERVICE.md#pagination-best-practices).

## Performance best practices

- **Aggregate/filter/reduce in DuckDB, not Java.** A `GROUP BY` that turns 30M cached rows into
  500 result rows is the intended shape; returning raw rows for Java-side aggregation defeats the
  purpose of the cache.
- **Filter on the columns you'll actually query by** (e.g. `fiscal_year`) - DuckDB's columnar
  storage makes selective filters cheap.
- **Watch join cardinality** on multi-dataset queries - see the three-dataset warning above.
- **Version behavior**: every dataset in a query is pinned to its ACTIVE version at the moment the
  query starts. A refresh that completes mid-query never changes results for that in-flight query;
  the next query call automatically sees the new version. Independent datasets in a join refresh
  independently - see `DuckDbQueryEngineTest.independentDatasetRefreshDoesNotAffectOtherDatasetVersionPinnedByQuery`.

## When source SQL must change

If the column you need already exists in the cached table, add analytical SQL only - no refresh
needed. If it doesn't exist yet, you must:

1. Add the column to the dataset's `source-sql` (`datacache/dremio/<dataset>.sql`).
2. Refresh the dataset (`POST /api/v1/cache/admin/datasets/<dataset>/refresh` or wait for its
   cron).
3. Only then reference the column in analytical SQL - it does not exist in the currently ACTIVE
   version until that refresh completes and activates.

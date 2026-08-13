# Data Cache Developer Guide

A complete, start-to-finish guide for a Java developer who has never seen this project before.
Every command, property name, class name, and JSON shape below matches the actual implementation -
nothing here is aspirational. Where useful, this guide points at the specialized document that
goes deeper on a topic; you do not need to read those to follow this guide end to end.

---

## 1. What this service does

```
Dremio is the SOURCE. DuckDB is the CACHE and QUERY ENGINE.

Dremio is contacted only during a refresh.
Normal application queries run against DuckDB - never against Dremio.
```

```
                    REFRESH PATH  (runs during a refresh only)

Dremio
   |  Apache Arrow Flight SQL
   v
Arrow record batches (bounded memory - one batch in flight at a time)
   |
   v
DuckDB Appender bulk writer
   |
   v
ACTIVE dataset version (a real .duckdb file on disk)


                    QUERY PATH  (runs on every application request)

Application code (Java or REST)
   |
   v
a registered query name, e.g. "cfo-summary"
   |
   v
DuckDB ACTIVE dataset version(s) - attached transparently, joined, filtered, aggregated
   |
   v
result (paginated)
```

The refresh path and query path are completely independent processes that only meet at one point:
the ACTIVE version pointer that `MetadataStore` maintains for each dataset. This is what makes
zero-downtime refresh possible - see [07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md).

## 2. Cache directory

```
/data/cache/                              <- data-cache.duckdb.base-directory

    metadata/
        cache_metadata.duckdb             <- persistent version metadata (one small DuckDB file)

    financial/
        financial_v11.duckdb              <- PREVIOUS
        financial_v12.duckdb              <- ACTIVE

    organization/
        organization_v5.duckdb            <- PREVIOUS
        organization_v6.duckdb            <- ACTIVE

    headcount/
        headcount_v3.duckdb               <- PREVIOUS
        headcount_v4.duckdb               <- ACTIVE

    temp/                                 <- data-cache.duckdb.temp-directory (DuckDB spill space)
```

Each dataset has its own subdirectory and its own independent `.duckdb` files - datasets are never
combined into one file. Version state (`BUILDING`, `VALIDATING`, `ACTIVE`, `PREVIOUS`, `FAILED`) is
tracked in `metadata/cache_metadata.duckdb`, not in the filenames themselves - a `..._building`
suffix during a build is just a naming convention, the metadata row is authoritative.

**Never manually delete an ACTIVE file.** It's safe to delete files after confirming (via
`GET /api/v1/cache/admin/datasets/<name>`) they are neither the `activeVersion` nor the
`previousVersion` - but the system already does this automatically once a version falls outside
the retained window and has no in-flight readers (`VersionManager`, see
[07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md#retention-only-active--previous)), so
manual cleanup should rarely be necessary. Full detail: [06-DUCKDB-CACHE.md](06-DUCKDB-CACHE.md).

## 3. First application start

Scenario: `/data/cache` is completely empty (first deployment).

```
No files exist anywhere under /data/cache.

Application starts.

financial:     no cache
organization:  no cache
headcount:     no cache
        |
        v
DataCacheStartupCoordinator sees none of them has an ACTIVE version and automatically
triggers an initial load for each - through the exact same refresh pipeline used everywhere
else - regardless of each dataset's configured startup.mode. No manual API call is needed.
        |
        v
Dremio -> Arrow -> DuckDB, for each dataset (bounded by data-cache.refresh.max-concurrent-datasets)
        |
        v
financial V1 ACTIVE
organization V1 ACTIVE
headcount V1 ACTIVE
```

Whether the application *itself* waits for this to finish, or reports ready immediately and lets
these loads finish in the background, is controlled by `data-cache.startup.execution-mode`:

- **`ASYNC`** (default): the application reports ready immediately. Datasets still loading answer
  queries with a clean `DATASET_NOT_AVAILABLE` (HTTP 503) error until their first load completes.
- **`BLOCK_UNTIL_REQUIRED_CACHE_READY`**: the startup sequence additionally waits (up to
  `data-cache.startup.timeout`, default 30 minutes) for every dataset marked `required: true`
  (the default) to finish its first load before letting the readiness probe go green. It never
  hangs forever - on timeout it simply stops waiting and loading continues in the background.

Full detail, including every failure mode: [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md).

## 4. Normal restart

Scenario: `financial V10 ACTIVE`, `financial V9 PREVIOUS` already exist on disk, and the dataset is
configured with the default `startup.mode: USE_EXISTING_OR_CREATE`.

```
Application restarts
        |
        v
Metadata recovered (StartupRecoveryService reconciles metadata against physical files -
discards any interrupted BUILDING/VALIDATING version from a crash, checks the ACTIVE file
still exists)
        |
        v
V10 is a valid ACTIVE version -> reused immediately, no refresh triggered just because the
process restarted
        |
        v
Queries work immediately using V10
        |
        v
The dataset's configured refresh-cron continues to fire on its own schedule, exactly as before
```

## 5. Always-refresh on start

Scenario: `financial V10 ACTIVE` exists, and the dataset is configured:

```yaml
data-cache:
  datasets:
    financial:
      startup:
        mode: ALWAYS_REFRESH
```

```
Application starts
        |
        v
V10 recovered and made available to queries IMMEDIATELY - exactly like USE_EXISTING_OR_CREATE
        |
        v
Because mode is ALWAYS_REFRESH, a new version additionally starts building in the background
through the normal refresh pipeline: Dremio -> Arrow -> financial_v11_building.duckdb
        |
        v
Queries keep using V10 throughout - never blocked, never interrupted
        |
        v
V11 finishes, passes validation
        |
        v
Atomic cutover: V11 ACTIVE, V10 PREVIOUS (V9 cleaned up once safe, per the normal 2-version
retention policy)
        |
        v
New queries now use V11; any query that had already started against V10 finishes against V10
```

This is never a destructive replace - the old version is never deleted before the new one is fully
built and validated. See
[23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md#alwaysrefresh) for the failure case
(what happens if the background V11 build fails).

## 6. How to configure Dremio

```yaml
data-cache:
  dremio:
    host: ${DREMIO_HOST}
    port: ${DREMIO_PORT:32010}
    username: ${DREMIO_USERNAME}
    password: ${DREMIO_PASSWORD}
    ssl-enabled: true
    connect-timeout: 30s
    query-timeout: 30m
```

**Local environment variables:**

```bash
export DREMIO_HOST=dremio.example.internal
export DREMIO_PORT=32010
export DREMIO_USERNAME=svc_datacache
export DREMIO_PASSWORD='use-a-real-secret-here'
```

**OpenShift Secret** (never commit real values - see [`openshift/secret.example.yaml`](../openshift/secret.example.yaml)):

```bash
oc create secret generic data-cache-dremio-credentials \
    --from-literal=DREMIO_USERNAME=svc_datacache \
    --from-literal=DREMIO_PASSWORD='********'
```

...referenced in the Deployment via `envFrom: secretRef: name: data-cache-dremio-credentials` (see
[`openshift/deployment.yaml`](../openshift/deployment.yaml)). The password is never logged -
`DremioProperties.toString()` deliberately omits it. Full detail:
[04-DREMIO-CONFIGURATION.md](04-DREMIO-CONFIGURATION.md).

## 7. How to create a dataset

Worked example: `financial`.

**Step 1** - create the source SQL, `src/main/resources/datacache/dremio/financial.sql`:

```sql
SELECT fiscal_year, fiscal_month, business_unit, cost_center, account, actual_amount, forecast_amount
FROM finance.financial_data
```

**Step 2** - add YAML:

```yaml
data-cache:
  datasets:
    financial:
      enabled: true
      table-name: financial
      source-sql: classpath:datacache/dremio/financial.sql
      startup:
        mode: USE_EXISTING_OR_CREATE
      refresh-cron: "0 0 1,4,7,10,13,16,19 * * *"
      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s
      validation:
        minimum-row-count: 1000000
        required-columns: [fiscal_year, fiscal_month, cost_center, actual_amount, forecast_amount]
```

**Step 3** - start (or restart) the app. The mandatory auto-create rule loads it automatically; no
manual call is required (see section 3 above). To trigger it explicitly instead:

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/financial/refresh
```

**Step 4** - check status:

```bash
curl localhost:8080/api/v1/cache/admin/datasets/financial
```

**Step 5** - the physical file now exists: `${DATA_CACHE_BASE_DIR}/financial/financial_v1.duckdb`.

**No Java class of any kind was required** - no Entity, Repository, Loader, Scheduler, Service, or
Controller. Full detail: [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md).

## 8. Adding a fourth dataset

The framework is fully generic - adding `expense` follows the identical two-step recipe as
`financial` above (new source SQL file + new YAML entry). See
[12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md) for the complete worked example using
`expense` specifically.

## 9. How querying works

Registered queries are **not** Dremio queries. They run entirely inside DuckDB, against whatever is
currently cached, referring to each dataset by its **stable logical name** (the dataset's YAML
key) - never a versioned table name like `financial_v11` and never a file path. The query engine
resolves each logical name to the dataset's current ACTIVE physical file transparently, at the
moment the query starts (`ATTACH ... (READ_ONLY)` under a private alias, then a view named after
the logical name - see [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md)):

```sql
SELECT fiscal_month, SUM(actual_amount)
FROM financial
WHERE fiscal_year = :fiscalYear
GROUP BY fiscal_month
```

## 10. Single dataset query - complete example

`src/main/resources/datacache/query/financial-summary.sql`:

```sql
SELECT fiscal_year, fiscal_month, SUM(actual_amount) AS actual, SUM(forecast_amount) AS forecast
FROM financial
WHERE fiscal_year = :fiscalYear
GROUP BY fiscal_year, fiscal_month
ORDER BY fiscal_month
```

`application.yml`:

```yaml
data-cache:
  queries:
    financial-summary:
      sql: classpath:datacache/query/financial-summary.sql
      datasets: [financial]
```

Java:

```java
PagedQueryResult result = queryService.execute("financial-summary", Map.of("fiscalYear", 2026), 0, 100);
```

REST:

```bash
curl -X POST localhost:8080/api/v1/cache/query/financial-summary \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"fiscalYear":2026},"page":0,"size":100}'
```

Response (shape of `PagedQueryResult`):

```json
{
  "queryName": "financial-summary",
  "columns": [{"name": "fiscal_year", "sqlType": "INTEGER"}, ...],
  "rows": [{"fiscal_year": 2026, "fiscal_month": 1, "actual": 120000.00, "forecast": 118500.00}, ...],
  "page": 0, "size": 100, "totalRows": 12, "hasNext": false,
  "executionTimeMs": 4,
  "datasetVersionsUsed": {"financial": 12}
}
```

## 11. Two-dataset JOIN - complete example

`src/main/resources/datacache/query/cfo-summary.sql`:

```sql
SELECT o.cio, o.business_unit, SUM(f.actual_amount) AS actual, SUM(f.forecast_amount) AS forecast
FROM financial f
JOIN organization o ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY o.cio, o.business_unit
```

```yaml
data-cache:
  queries:
    cfo-summary:
      sql: classpath:datacache/query/cfo-summary.sql
      datasets: [financial, organization]
```

Internally, at execution time:

```
financial's current ACTIVE version (say V12) and organization's current ACTIVE version (say V6)
are each "pinned" (reader-refcounted so neither can be deleted mid-query)
        |
        v
Both physical files are ATTACHed under private aliases in one isolated DuckDB connection
        |
        v
Views named "financial" and "organization" are created over those attached files
        |
        v
The registered SQL above executes unmodified against those views
        |
        v
Both pins are released once the query completes
```

The whole join, filter, and aggregation happens inside DuckDB - only the final (paginated) result
rows ever cross into Java. See [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md).

## 12. Three-dataset JOIN

```sql
SELECT o.cio, o.department, h.fiscal_year, SUM(h.headcount_count) AS total_headcount, SUM(f.actual_amount) AS total_cost
FROM headcount h
JOIN organization o ON h.cost_center = o.cost_center
JOIN financial f     ON f.cost_center = o.cost_center AND f.fiscal_year = h.fiscal_year
WHERE h.fiscal_year = :fiscalYear
GROUP BY o.cio, o.department, h.fiscal_year
```

**Join-grain warning**: before joining three tables, confirm each one's actual grain (what
combination of columns is unique per row). If `financial` has a finer grain than `headcount` on
the join key (e.g. `financial` also varies by `fiscal_month` but isn't joined on that column here),
each `headcount` row can multiply across every matching `financial` row - a many-to-many "fan-out"
that silently inflates `SUM()` results. This is a financial-correctness risk, not just a
performance one. When in doubt, pre-aggregate one side to the join grain first, in a CTE (next
section), before joining.

## 13. CTEs, GROUP BY, window functions

Standard, unrestricted DuckDB read-only SQL is supported - `JOIN`/`LEFT JOIN`, `GROUP BY`,
`ORDER BY`, `HAVING`, CTEs, window functions, `SUM`/`COUNT`/`AVG`/`MIN`/`MAX`, arbitrary filters:

```sql
WITH monthly AS (
    SELECT fiscal_year, fiscal_month, SUM(actual_amount) AS actual
    FROM financial
    WHERE fiscal_year = :fiscalYear
    GROUP BY fiscal_year, fiscal_month
)
SELECT
    fiscal_month,
    actual,
    SUM(actual) OVER (ORDER BY fiscal_month) AS running_total,
    RANK() OVER (ORDER BY actual DESC) AS rank_in_year
FROM monthly
ORDER BY fiscal_month
```

More examples (including `ROW_NUMBER`, `LAG`, `HAVING`):
[08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md).

## 14. Query parameters

```sql
WHERE fiscal_year = :fiscalYear AND cio = :cio
```

Java: `Map.of("fiscalYear", 2026, "cio", "Technology")`. REST:
`{"parameters": {"fiscalYear": 2026, "cio": "Technology"}}`.

Supported value types are whatever `PreparedStatement.setObject` accepts for the DuckDB JDBC
driver - `String`, `Integer`/`Long`, `Double`/`BigDecimal`, `Boolean`, `java.time.LocalDate`/
`LocalDateTime`. Values are always bound through `PreparedStatement`, never string concatenation -
`NamedParameterSqlBinder` parses `:name` tokens character-by-character, correctly skipping ones
that appear inside string literals, quoted identifiers, or SQL comments, so a value containing
quotes, `;`, `--`, or `/* */` is always treated as literal data, never as SQL syntax. A parameter
the SQL requires but the caller didn't supply raises `MissingQueryParameterException` (HTTP 400)
rather than silently binding `null`.

## 15. Query pagination

30 million cached rows must never become a 30-million-row JSON response. `page`/`size` map
directly to DuckDB `LIMIT`/`OFFSET` - rows beyond the requested page are never read into Java at
all. The total row count is computed in the *same* DuckDB execution via a `COUNT(*) OVER()` window
function, not a second full scan. `data-cache.pagination.max-page-size` (default 5000, overridable
per query) caps whatever `size` a caller requests. Practically: design analytical queries to
aggregate/filter down to a reasonable result size in DuckDB (see section 12's fan-out warning) -
pagination is for browsing an already-reduced result, not for streaming a raw table out row by
row. Full detail: [10-USING-QUERY-SERVICE.md](10-USING-QUERY-SERVICE.md#pagination-best-practices).

## 16. When source SQL must change

Say `financial.sql` currently caches `actual_amount` and `forecast_amount`, and a new report needs
`currency_code`, which isn't cached yet:

```
currency_code is not in the cached "financial" table
        |
        v
Edit datacache/dremio/financial.sql (in Dremio's source, the Dremio-side query) to select it too
        |
        v
Refresh financial (POST /api/v1/cache/admin/datasets/financial/refresh, or wait for its cron)
        |
        v
The new ACTIVE version's table now has a currency_code column
        |
        v
Only now can a DuckDB analytical query SELECT currency_code
```

If the column you need is **already** cached, you only need step "add analytical SQL" - no source
SQL change, no refresh. This distinction (source SQL vs. analytical SQL) is the single most
important thing to internalize about this system - see
[05-DATASET-CONFIGURATION.md](05-DATASET-CONFIGURATION.md#source-sql-vs-analytical-sql).

## 17. Scheduled refresh

```yaml
data-cache:
  datasets:
    financial:
      refresh-cron: "0 0 1,4,7,10,13,16,19 * * *"   # every 3 hours
    organization:
      refresh-cron: "0 30 2,8,14,20 * * *"           # every 6 hours
    headcount:
      refresh-cron: "0 15 3,9,15,21 * * *"           # every 6 hours, offset
```

Each dataset gets its own independently-firing `CronTrigger`, registered automatically by
`DynamicRefreshScheduler` for every enabled, cron-configured dataset - there is no per-dataset
`@Scheduled` method anywhere in the codebase to write or maintain. Each dataset's version history
is completely independent: refreshing `financial` never touches `organization`'s files, lock, or
schedule.

## 18. Manual refresh

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/financial/refresh
```

Response is a `DatasetRefreshResult` (see [11-REST-API.md](11-REST-API.md)) whose `outcome` is one
of:

- `SUCCESS` - new version built, validated, and activated
- `FAILED` - see `errorCode`/`errorMessage`; the previous ACTIVE version, if any, is untouched
- `VALIDATION_FAILED` - the build succeeded but a configured validation rule rejected it; previous ACTIVE version untouched
- `ALREADY_RUNNING` - a refresh for this dataset is already in progress (per-dataset `RefreshLock`)
- `DISABLED` - the dataset is configured `enabled: false`

`POST /api/v1/cache/admin/refresh-all` does the same for every enabled dataset, bounded by
`data-cache.refresh.max-concurrent-datasets`.

## 19. Version switch, step by step

```
financial V10 ACTIVE
        |
        v
refresh triggered (any source: startup, cron, or manual - identical pipeline either way)
        |
        v
financial V11 BUILDING            <- queries still use V10
        |
        v
V11 VALIDATING                    <- queries still use V10
        |
        v
V11 passes validation
        |
        v
Atomic switch (one metadata transaction): V11 ACTIVE, V10 PREVIOUS
        |
        v
New queries pin V11. Any query that had already pinned V10 before the switch keeps running
against V10 until it finishes - its result is never affected by the switch happening mid-query.
V10 is deleted only once every such reader has released it AND it has fallen outside the
retained window (default: keep ACTIVE + 1 PREVIOUS).
```

## 20. Failure, no downtime

```
financial V10 ACTIVE

V11 refresh starts, streams 15,000,000 of an expected 30,000,000 rows, then the network to
Dremio drops

V11 marked FAILED - its partial file is deleted, V10 is never touched

Queries continued working against V10 throughout, without interruption

If the failure was classified retryable (network/timeout errors are; bad SQL and validation
failures are not), the configured retry policy (max-attempts/backoff) already tried again
automatically before this final FAILED state was reached
```

## 21. Queries during a refresh

**A refresh never blocks normal query traffic.** This holds regardless of what triggered the
refresh (startup, cron, or manual) and regardless of `startup.mode`. The mechanism is version
pinning: every query call pins each dataset's *current* ACTIVE version at the moment it starts, and
a refresh's atomic cutover only ever changes what *new* queries see - see section 19 above and
[07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md#query-version-pinning).

## 22. Status and health

```bash
curl localhost:8080/api/v1/cache/admin/datasets/financial
```

```json
{
  "datasetName": "financial",
  "enabled": true,
  "activeVersion": 12,
  "previousVersion": 11,
  "activeRowCount": 31284901,
  "lastSuccessfulRefresh": "2026-08-13T04:00:12Z",
  "lastRefreshStatus": "SUCCESS",
  "lastRefreshDurationMs": 49965,
  "lastError": null,
  "refreshInProgress": false
}
```

`GET /actuator/health` reports two independent components: `dataCache` (is the cache itself
queryable - stays UP even if the latest refresh failed, as long as an older ACTIVE version still
serves traffic) and `dremioSource` (is Dremio reachable right now - independent of cache health). A
third, `dataCacheReadiness`, only affects the readiness probe under
`BLOCK_UNTIL_REQUIRED_CACHE_READY` - see [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md).
Full detail: [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md).

## 23. Monitoring

Every refresh records a fine-grained timing breakdown (Micrometer + structured logs) that
pinpoints the bottleneck without guesswork:

| Symptom | Likely cause |
|---|---|
| `dremioSetupMs`/`timeToFirstBatchMs` high | Dremio itself (planning, or slow to produce the first batch) |
| `arrowTransferMs` high relative to the others | Network bandwidth or row width between Dremio and this service |
| `duckDbWriteMs` high | Storage/DuckDB - check disk throughput, `duckdb.threads` |
| Query `executionTimeMs` high | Join cardinality (see section 12) or insufficient filtering before aggregation |

Full metric names and diagnosis recipes:
[15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md), [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md).

## 24. Troubleshooting

See [19-TROUBLESHOOTING.md](19-TROUBLESHOOTING.md) for the full table. Startup-lifecycle-specific
issues are in [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md) and the table below:

| Problem | Cause | Fix |
|---|---|---|
| Cache doesn't load at startup | Check `enabled: true` is set; the mandatory auto-create rule cannot be disabled once a dataset is enabled | Verify `data-cache.datasets.<name>.enabled` |
| `ALWAYS_REFRESH` doesn't seem to run again | It only refreshes once per process start, not on every request | This is correct - it is a startup-time action, not continuous; use `refresh-cron` for recurring refreshes |
| Query returns `DATASET_NOT_AVAILABLE` right after startup | Dataset is still on its mandatory first load (`ASYNC` mode) | Poll `GET /api/v1/cache/admin/datasets/<name>` until `activeVersion` is non-null, or switch to `BLOCK_UNTIL_REQUIRED_CACHE_READY` |
| Readiness probe never turns green | `BLOCK_UNTIL_REQUIRED_CACHE_READY` and a `required: true` dataset's first load is failing | Check its status/`lastError`; mark it `required: false` if it shouldn't gate readiness |

## 25. Embedding into another service

Assume an existing Spring Boot 3 service, `existing-finance-service`, wants this cache without
running the standalone demo app.

The entire cache implementation is one Java package, `com.enterprise.datacache.feature.cache`, and
one resources folder, `src/main/resources/datacache/`. Copy both into
`existing-finance-service` (no package rename needed), add the cache-specific Gradle dependencies,
add a `data-cache.*` configuration block (sections 6-7 above, or the full
[03-CONFIGURATION-REFERENCE.md](03-CONFIGURATION-REFERENCE.md)) to its own `application.yml`, and
add exactly one annotation to its `@SpringBootApplication` class:

```java
@SpringBootApplication
@EnableDataCache
public class ExistingFinanceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExistingFinanceServiceApplication.class, args);
    }
}
```

`@EnableDataCache` explicitly imports `DataCacheConfiguration`, which registers every cache bean -
no component-scan of `com.enterprise.datacache` is relied upon, so this works regardless of where
`feature.cache` ends up living inside the host application.

```java
@Service
public class FinanceService {

    private final DataCacheQueryService queryService;

    public FinanceService(DataCacheQueryService queryService) {
        this.queryService = queryService;
    }

    public PagedQueryResult getCfoSummary(int fiscalYear) {
        return queryService.execute("cfo-summary", Map.of("fiscalYear", fiscalYear), 0, 100);
    }
}
```

`DataCacheRefreshService` and `DataCacheStatusService` inject the same way.

**Not required**: the standalone demo app's `DataCacheApplication` class, its `Dockerfile`, or the
`openshift/*.yaml` manifests - those exist only to make `feature.cache` independently runnable as
its own service. Dremio integration, Arrow streaming, the DuckDB writer, metadata, versioning,
refresh scheduling/retry, validation, startup recovery, the startup cache lifecycle, and the query
engine are all already complete inside `feature.cache` - nothing here needs to be finished by the
embedding application. Full detail, including the exact dependency versions, environment
variables, and a complete before/after directory tree:
[INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md](INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md).

---

## Quick-reference tables

### Cache lifecycle

| Scenario | Existing cache? | `startup.mode` | Behavior |
|---|---|---|---|
| First deployment | No | any | Auto-create: initial load triggered automatically |
| Restart | Yes | `USE_EXISTING_OR_CREATE` | Reuse ACTIVE immediately, no startup refresh |
| Restart | Yes | `ALWAYS_REFRESH` | Reuse ACTIVE immediately + build a new version in the background |
| Startup refresh fails | Yes | `ALWAYS_REFRESH` | Existing ACTIVE version kept; failure recorded |
| First load fails | No | any | Dataset unavailable; status reports failure; retry policy already applied |
| Scheduled refresh succeeds | Yes | N/A | New ACTIVE version, old one becomes PREVIOUS |
| Scheduled refresh fails | Yes | N/A | Current ACTIVE version kept |

(Full version, with diagrams: [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md).)

### What to change for a given need

| Need | What you change |
|---|---|
| New aggregation over already-cached data | New query `.sql` file + query YAML entry |
| New join of already-cached datasets | New query `.sql` file + query YAML entry |
| New filter parameter on an existing query | Edit the query `.sql` file only |
| New column that isn't cached yet | Dremio source `.sql` + refresh the dataset |
| New dataset entirely | New source `.sql` file + dataset YAML entry |
| New/changed refresh time | Configuration only (`refresh-cron`) |
| Change first-start vs. restart behavior | `startup.mode` (per dataset) / `data-cache.startup.execution-mode` (global) |

No scenario in either table requires writing a new Java class.

## How to know the cache is actually working

```bash
# 1. Confirm a refresh succeeded and see its full timing breakdown
curl -X POST localhost:8080/api/v1/cache/admin/datasets/financial/refresh

# 2. Confirm the physical DuckDB file exists
ls -la ${DATA_CACHE_BASE_DIR}/financial/

# 3. Confirm the ACTIVE version is registered, with a real row count
curl localhost:8080/api/v1/cache/admin/datasets/financial | jq '.activeVersion, .activeRowCount'

# 4. Confirm an analytical query actually returns data
curl -X POST localhost:8080/api/v1/cache/query/financial-summary \
  -H 'Content-Type: application/json' -d '{"parameters":{"fiscalYear":2026}}' | jq '.rows | length'
```

If all four succeed, the cache is genuinely working end to end - not just "the app started".

## Running tests and the benchmark

```bash
./gradlew clean build                                                   # full build + all tests
./gradlew runBenchmark --args="--rows=5000000"         # DuckDB writer throughput
```

See [01-QUICK-START.md](01-QUICK-START.md) and [15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md).

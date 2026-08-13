# 17. Metrics and Monitoring

All metrics are recorded by `DataCacheMetrics` (`com.enterprise.datacache.metrics`) via
Micrometer. Tag values are always dataset/query names drawn from configuration (a small, bounded
set) - never row data or request parameters - to keep cardinality bounded.

## Refresh metrics

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `datacache.refresh.duration` | Timer | `dataset`, `outcome` | Total refresh wall-clock time |
| `datacache.refresh.phase.duration` | Timer | `dataset`, `phase` | One of `dremio_setup`, `time_to_first_batch`, `arrow_transfer`, `duckdb_write`, `validation`, `activation` |
| `datacache.refresh.rows` | DistributionSummary | `dataset` | Rows loaded per refresh |
| `datacache.refresh.bytes` | DistributionSummary | `dataset` | Bytes written per refresh |
| `datacache.refresh.rows_per_second` | Gauge | `dataset` | Most recent refresh's throughput |
| `datacache.refresh.count` | Counter | `dataset`, `outcome` | Refresh attempts by outcome |
| `datacache.refresh.failures` | Counter | `dataset`, `errorCode` | Failed attempts by error code |

## Query metrics

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `datacache.query.duration` | Timer | `query`, `outcome` | End-to-end query execution time |
| `datacache.query.count` | Counter | `query`, `outcome` | Query calls by outcome |

## Version metrics

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `datacache.version.active_readers` | Gauge | `dataset` | In-flight readers pinning a version (see `VersionManager`) |

## Standard JVM/Spring metrics

Because `data-cache-app` pulls in `spring-boot-starter-actuator`, the usual JVM/HTTP/Tomcat
metrics are also exposed under `/actuator/metrics` and `/actuator/prometheus`.

## Health

Two independent Actuator health indicators, both `@ConditionalOnClass(HealthIndicator.class)` so
they only register when Actuator is actually present:

- **`dataCache`** (`DataCacheHealthIndicator`) - reflects whether the metadata store is reachable
  and reports each dataset's active version/row count/last refresh status as `details`. This stays
  **UP** even if the most recent refresh failed, as long as an older ACTIVE version still exists
  and can serve queries.
- **`dremioSource`** (`DremioSourceHealthIndicator`) - calls `DremioSource.isHealthy()`
  independently. A Dremio outage shows up here without dragging down `dataCache` or the
  aggregate `/actuator/health` status for query serving in a misleading way.

## Structured logs

Refresh completion is logged once per attempt at `INFO` (success) or `WARN` (failure), e.g.:

```
event=dataset-refresh-completed dataset=financial version=11 rows=31284901 durationMs=49965 rowsPerSecond=625894.2 status=SUCCESS
```

No per-row or per-batch logs at `INFO` (batch-level detail is `DEBUG` only), and credentials are
never logged (`DremioProperties.toString()` omits the password field entirely).

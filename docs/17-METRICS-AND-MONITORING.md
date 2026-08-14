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

## Live progress gauges

Bound lazily, tagged only by `dataset` (never `version`, to keep cardinality bounded regardless of
how many versions a dataset accumulates over the application's lifetime) - read through to whatever
`RefreshProgress` is currently (or was most recently) tracked for that dataset, `0` if none has
ever run. See [DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs](DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs).

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `datacache.refresh.progress.rows_processed` | Gauge | `dataset` | Rows processed by the current/last refresh attempt |
| `datacache.refresh.progress.batches_processed` | Gauge | `dataset` | Arrow batches processed by the current/last attempt |
| `datacache.refresh.progress.elapsed_ms` | Gauge | `dataset` | Elapsed time for the current attempt; frozen once it finishes |
| `datacache.refresh.progress.rows_per_second` | Gauge | `dataset` | Average rows/sec for the current/last attempt |

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

Because this project pulls in `spring-boot-starter-actuator`, the usual JVM/HTTP/Tomcat
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

A refresh logs a structured sequence of INFO events - started, Dremio query started, first batch,
periodic progress (rate-limited by `data-cache.logging.progress.*`), load completed, validation,
activation, and a final completed/failed summary - see
[DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs](DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs)
for the full sequence and field reference. The terminal summary line, e.g.:

```
event=dataset-refresh-completed dataset=financial version=13 status=SUCCESS rows=31842511 batches=486 totalDurationMs=1374122 dremioSetupMs=... dremioFirstBatchMs=41322 arrowTransferMs=... duckDbWriteMs=... validationMs=... activationMs=... averageRowsPerSecond=23172.4
```

or, on failure:

```
event=dataset-refresh-failed dataset=financial failedVersion=13 existingActiveVersion=12 existingActivePreserved=true rowsProcessed=15342811 elapsedMs=... stage=STREAMING errorCode=DREMIO_SOURCE_ERROR error=...
```

No per-row or per-batch logs at `INFO` (batch-level detail is `DEBUG` only), and credentials are
never logged (`DremioProperties.toString()` omits the password field entirely).

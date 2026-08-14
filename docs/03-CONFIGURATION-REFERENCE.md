# 03. Configuration Reference

Every property below is bound by `DataCacheProperties` (`com.enterprise.datacache.config`) and
validated eagerly at startup by `DataCacheConfigurationValidator` (invalid config fails fast with
a clear message, not a cryptic runtime error). Types shown are the actual Java field types -
`Duration` accepts Spring's simple unit suffixes (`10s`, `30m`, `1h`); `DataSize` accepts unit
suffixes (`2GB`, `512MB`).

## Top level

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `data-cache.enabled` | boolean | no | `true` | Master switch. `false` registers zero beans. |

## `data-cache.dremio.*`

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `host` | String | yes (to refresh) | - | Dremio coordinator hostname |
| `port` | int | no | `32010` | Arrow Flight SQL port |
| `username` | String | yes (to refresh) | - | Basic-auth username |
| `password` | String | yes (to refresh) | - | Basic-auth password. Never logged. |
| `ssl-enabled` | boolean | no | `true` | Negotiate TLS with the Flight endpoint |
| `trusted-certificates-path` | String | no | - | PEM bundle path; unset uses the JVM default trust store |
| `connect-timeout` | Duration | no | `30s` | Reserved for connection-level timeouts |
| `query-timeout` | Duration | no | `30m` | Applied as a Flight `CallOption` timeout on execute/getStream |
| `verification-sql` | String | no | `SELECT 1` | SQL used by the health check / live-verification task |

## `data-cache.startup.*`

Global startup execution strategy - see [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md).

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `execution-mode` | `StartupExecutionMode` (`ASYNC` \| `BLOCK_UNTIL_REQUIRED_CACHE_READY`) | no | `ASYNC` | Whether startup cache loads run purely in the background or the coordinator waits for required datasets |
| `timeout` | Duration | no | `30m` | Maximum time `BLOCK_UNTIL_REQUIRED_CACHE_READY` waits before giving up (loading continues in the background regardless) |

## `data-cache.logging.progress.*`

Controls INFO-level progress logging during a dataset refresh - see
[DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs](DATA-CACHE-DEVELOPER-GUIDE.md#monitoring-a-cache-refresh-from-logs)
for the full event reference. A progress line fires when `row-interval` rows have been processed
since the last one OR `time-interval` has elapsed since the last one, whichever comes first.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `enabled` | boolean | no | `true` | Master switch for `event=dataset-load-progress` lines |
| `row-interval` | long | no | `1000000` | Emit a line after at least this many rows since the last one |
| `time-interval` | Duration | no | `30s` | Emit a line after at least this much time since the last one |
| `include-file-size` | boolean | no | `true` | Include the BUILDING file's current on-disk size (stat'd only when a line is about to be emitted) |
| `include-batch-count` | boolean | no | `true` | Include the running Arrow batch count |

## `data-cache.arrow.*`

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `max-memory` | DataSize | no | `2GB` | Hard cap on the shared root `BufferAllocator` |

## `data-cache.duckdb.*`

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `base-directory` | String | no | `/data/cache` | Root directory; one subdirectory per dataset |
| `temp-directory` | String | no | `/data/cache/temp` | DuckDB spill/temp directory |
| `memory-limit` | DataSize | no | `8GB` | `PRAGMA memory_limit` per DuckDB connection |
| `threads` | int | no | `8` | `PRAGMA threads` per DuckDB connection |
| `max-versions` | int | no | `2` | Retained completed versions per dataset; must be >= 2 |

## `data-cache.refresh.*`

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `max-concurrent-datasets` | int | no | `2` | Size of the bounded refresh executor |
| `recover-on-startup` | boolean | no | `true` | Run `StartupRecoveryService` before the startup cache lifecycle runs |

## `data-cache.pagination.*`

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `default-page-size` | int | no | `100` | Used when a query request omits `size` |
| `max-page-size` | int | no | `5000` | Hard cap on requested page size |

## `data-cache.datasets.<name>.*`

`<name>` is the stable logical/table name used in analytical SQL (`FROM <name>`).

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `enabled` | boolean | no | `true` | Disabled datasets are skipped by scheduling, the startup coordinator, and validation |
| `required` | boolean | no | `true` | Whether this dataset counts toward readiness under `BLOCK_UNTIL_REQUIRED_CACHE_READY` (see [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md)) |
| `table-name` | String | no | `<name>` | Physical table name created inside the dataset's DuckDB file |
| `source-sql` | String | yes | - | Resource location of the Dremio source SQL (e.g. `classpath:datacache/dremio/financial.sql`) |
| `refresh-cron` | String | no | - | 6-field Spring cron (with seconds); blank disables scheduling |
| `startup.mode` | `StartupMode` (`USE_EXISTING_OR_CREATE` \| `ALWAYS_REFRESH`) | no | `USE_EXISTING_OR_CREATE` | Per-dataset startup behavior - see [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md) |
| `retry.max-attempts` | int | no | `3` | Total attempts including the first |
| `retry.initial-delay` | Duration | no | `10s` | Delay before the first retry |
| `retry.multiplier` | double | no | `2.0` | Exponential backoff multiplier |
| `retry.max-delay` | Duration | no | `60s` | Backoff delay cap |
| `validation.minimum-row-count` | long | no | `0` | `0` disables the check |
| `validation.required-columns` | List\<String\> | no | `[]` | Case-insensitive column-name checks |
| `validation.sql` | String | no | - | Optional resource location of a custom read-only validation query |
| `progress.expected-row-count` | Long | no | - | Optional hint used only to compute `estimatedPercent` in progress logs/status; never affects refresh, validation, or retention |

## `data-cache.queries.<name>.*`

`<name>` is the query name passed to `DataCacheQueryService.execute` / the REST API.

| Property | Type | Required | Default | Description |
|---|---|---|---|---|
| `sql` | String | yes | - | Resource location of the analytical SQL (runs in DuckDB only) |
| `datasets` | List\<String\> | yes | - | Logical dataset names this query reads; must all exist and be enabled |
| `max-page-size` | Integer | no | global `pagination.max-page-size` | Per-query override |

## Resource locations

`source-sql`, `validation.sql`, and query `sql` are all Spring resource locations, resolved by
`SqlResourceLoader`:

- `classpath:datacache/dremio/financial.sql` - packaged inside a jar or `src/main/resources`
- `file:/etc/data-cache/queries/financial-summary.sql` - filesystem path (e.g. from a mounted
  ConfigMap)

A missing or empty resource fails startup with `InvalidConfigurationException`.

## Validation failures at startup

`DataCacheConfigurationValidator` rejects, before any bean touches Dremio or disk:

- blank `duckdb.base-directory`
- `duckdb.max-versions < 2`
- `duckdb.threads < 1`
- `refresh.max-concurrent-datasets < 1`
- `pagination.default-page-size` outside `[1, max-page-size]`
- any enabled dataset with a blank `source-sql`
- any enabled dataset with an invalid `refresh-cron` expression
- any dataset's `retry.max-attempts < 1` or `retry.multiplier <= 0`
- any query with a blank `sql`, an empty `datasets` list, or a `datasets` entry that does not
  name a configured, enabled dataset

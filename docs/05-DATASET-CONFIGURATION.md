# 05. Dataset Configuration

A dataset is a named cache built from one Dremio source query. Configuration lives entirely under
`data-cache.datasets.<name>` - see [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md) for
the step-by-step recipe.

## Anatomy

```yaml
data-cache:
  datasets:
    financial:                                        # <- logical/stable name, used in analytical SQL
      enabled: true
      table-name: financial                            # physical table name inside the .duckdb file
      source-sql: classpath:datacache/dremio/financial.sql
      refresh-cron: "0 0 1,4,7,10,13,16,19 * * *"       # 6-field Spring cron; omit to disable scheduling
      load-on-startup: true

      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s

      validation:
        minimum-row-count: 1000000
        required-columns:
          - fiscal_year
          - cost_center
        sql: classpath:datacache/validation/financial.sql
```

## Source SQL vs. analytical SQL

This is the single most important distinction in the whole system:

| | `source-sql` | Query `sql` |
|---|---|---|
| Runs in | **Dremio** | **DuckDB** |
| When | During refresh only | On every application request |
| Location | `datacache/dremio/*.sql` | `datacache/query/*.sql` |
| Purpose | Defines what gets cached | Reads/joins/aggregates what's cached |

If a query needs a column that already exists in the cache, add analytical SQL only. If it needs a
column that is **not yet cached**, `source-sql` must change and the dataset must be refreshed
before any query can use it - see [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md#when-source-sql-must-change).

## Table naming

The dataset's key in YAML (`financial`) is the **stable logical name** every analytical query uses
(`FROM financial`). `table-name` is the physical column-bearing table created inside each
version's `.duckdb` file - it defaults to the dataset key and rarely needs to differ.

## Cron scheduling

`refresh-cron` uses Spring's 6-field cron syntax (seconds first): `second minute hour day month
weekday`. Leaving it blank disables scheduled refresh for that dataset entirely - it can still be
triggered manually. Adding a cron to a new dataset requires no new scheduler code: see
`DynamicRefreshScheduler`, which registers one `CronTrigger` per configured dataset automatically.

## Retry

Retries apply only to failures classified as retryable (network/timeout/transient Dremio or
DuckDB errors) - see [07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md#retry). Invalid
configuration, bad SQL syntax, unsupported Arrow types, and validation failures are never retried.

## Validation

See [14-VALIDATION.md](14-VALIDATION.md) for the full rule set.

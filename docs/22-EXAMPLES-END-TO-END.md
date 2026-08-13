# 22. End-to-End Example

This walks through the complete sequence using the three datasets shipped as examples in
`data-cache-app`.

## 1. Configure the three datasets

Already present in `data-cache-app/src/main/resources/application.yml`:

```yaml
data-cache:
  datasets:
    financial: {...}
    organization: {...}
    headcount: {...}
```

(Replace the example `datacache/dremio/*.sql` table paths with your real Dremio sources first -
see [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md).)

## 2. Start the app and refresh all three

```bash
./gradlew :data-cache-app:bootRun
curl -X POST localhost:8080/api/v1/cache/admin/refresh-all
```

This runs all three refreshes concurrently (bounded by
`data-cache.refresh.max-concurrent-datasets`), each independently streaming Dremio Arrow batches
into its own DuckDB Appender writer.

## 3. Physical files created

```
${DATA_CACHE_BASE_DIR}/
├── metadata/cache_metadata.duckdb
├── financial/financial_v1.duckdb
├── organization/organization_v1.duckdb
└── headcount/headcount_v1.duckdb
```

## 4. `cfo-summary` is already registered

```yaml
data-cache:
  queries:
    cfo-summary:
      sql: classpath:datacache/query/cfo-summary.sql
      datasets: [financial, organization]
```

## 5. Execute it

```bash
curl -X POST localhost:8080/api/v1/cache/query/cfo-summary \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"fiscalYear":2026},"page":0,"size":50}'
```

The response's `datasetVersionsUsed` reports `{"financial": 1, "organization": 1}`.

## 6. Refresh only `financial` again

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/financial/refresh
```

`financial_v2.duckdb` is built, validated, and atomically activated; `financial_v1.duckdb` becomes
PREVIOUS (retained per `duckdb.max-versions`, deleted once no in-flight query still references it).
`organization` is completely untouched - its refresh lock, version history, and ACTIVE file are
independent.

## 7. The same `cfo-summary` call automatically uses the new version

```bash
curl -X POST localhost:8080/api/v1/cache/query/cfo-summary \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"fiscalYear":2026},"page":0,"size":50}'
```

No configuration change, no restart. `datasetVersionsUsed` now reports
`{"financial": 2, "organization": 1}` - `organization` stays at its current version while
`financial` picks up the new one, because each query pins each referenced dataset's ACTIVE version
independently at execution time. This exact scenario - refresh one dataset in a multi-dataset
join, verify the other is unaffected, verify the next query call picks up the new version - is
covered by an automated test:
`DuckDbQueryEngineTest.independentDatasetRefreshDoesNotAffectOtherDatasetVersionPinnedByQuery`.

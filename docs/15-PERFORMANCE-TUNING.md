# 15. Performance Tuning

## Benchmark tool

```bash
./gradlew runBenchmark --args="--rows=5000000 --batch-size=100000 --columns=8 --output=/tmp/bench.duckdb"
```

`DuckDbWriterBenchmark` (`com.enterprise.datacache.benchmark`) generates synthetic Arrow batches
directly (no intermediate Java DTOs - the same bounded-memory shape as the real refresh pipeline)
and writes them through the **exact same production `DuckDbDatasetWriter`** used by
`RefreshCoordinator`. It never fabricates numbers - every field in `BenchmarkResult` comes from an
actual measured run.

### Real measured results (this environment)

Run locally in this repository's CI/dev container (single vCPU-constrained sandbox, not
production-class hardware):

| Rows | Batch size | Extra columns | Elapsed | Rows/sec | Output size | MB/sec |
|---|---|---|---|---|---|---|
| 200,000 | 50,000 | 8 | 1.79 s | ~111,700 | 4.26 MB | 2.38 |
| 5,000,000 | 100,000 | 8 | 20.55 s | ~243,300 | 119.0 MB | 5.79 |

**What this measures**: only the DuckDB Appender write stage, in isolation, on this sandbox's
hardware. It does **not** include Dremio query planning/execution or Arrow network transfer time -
those depend entirely on your Dremio cluster, network, and source query shape, and cannot be
benchmarked without a live Dremio connection (none was available in this environment; see
[04-DREMIO-CONFIGURATION.md](04-DREMIO-CONFIGURATION.md#live-connectivity-verification-not-part-of-the-normal-build)).
Real-hardware production throughput will differ - re-run the benchmark on your actual target
infrastructure before relying on these numbers, and use the full-refresh timing breakdown below
for true end-to-end numbers against your real Dremio.

At the measured DuckDB-write-only rate, 5,000,000 rows purely for the write stage takes well under
a minute - meaning DuckDB ingestion itself is very unlikely to be the bottleneck for the
"5,000,000 rows in ~6-7 minutes" target; if that target is missed in production, the bottleneck is
almost certainly on the Dremio/Arrow side (see below).

## Full-refresh timing breakdown

Every `DatasetRefreshResult.timings()` (also emitted as structured log fields and Micrometer
timers - see [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md)) reports:

| Field | Meaning |
|---|---|
| `dremioSetupMs` | Time for `FlightSqlClient.execute()` to return a `FlightInfo` (Dremio planning) |
| `timeToFirstBatchMs` | Time from opening the stream to the first `FlightStream.next()` returning |
| `arrowTransferMs` | Cumulative time spent inside `FlightStream.next()` across all batches (network + deserialization) |
| `duckDbWriteMs` | Cumulative time spent inside `DuckDbDatasetWriter.writeBatch()` + `finish()` |
| `validationMs` | Time running configured validation rules |
| `activationMs` | Time to rename the file, update metadata, and atomically activate |
| `totalMs` | Wall-clock time for the whole refresh attempt |

## Bottleneck diagnosis

- **`dremioSetupMs` or `timeToFirstBatchMs` very high** -> the bottleneck is Dremio itself (query
  planning, or the underlying source is slow to produce its first batch). Tune the Dremio-side
  query/reflection, not this service.
- **`timeToFirstBatchMs` low, `arrowTransferMs` high** -> network bandwidth or row width between
  Dremio and this service. Consider selecting fewer/narrower columns in `source-sql`, or check
  network path/bandwidth.
- **`arrowTransferMs` low relative to `duckDbWriteMs`** -> storage/DuckDB is the bottleneck. Check
  the underlying volume's IOPS/throughput (especially on network-attached PVs), increase
  `data-cache.duckdb.threads`, or reduce column count if not all are needed.
- **`validationMs` high** -> usually the custom SQL validation query; simplify it or add a
  supporting index-equivalent (DuckDB benefits from filtering on already-clustered columns).

## Tuning knobs

| Setting | Effect |
|---|---|
| `data-cache.duckdb.threads` | DuckDB's internal parallelism for writes/checkpoints and query execution |
| `data-cache.duckdb.memory-limit` | Higher reduces spill-to-disk during large operations |
| `data-cache.duckdb.temp-directory` | Point at fast local disk, not network storage, if possible |
| `data-cache.arrow.max-memory` | Must comfortably exceed the largest in-flight batch's size |
| `data-cache.refresh.max-concurrent-datasets` | More concurrency helps only if storage/CPU has headroom |

Always re-run the benchmark (and a real refresh against your Dremio) after any tuning change - do
not assume a setting helped without measuring it.

# 07. Refresh and Versioning

## Version states

```
BUILDING -> VALIDATING -> ACTIVE -> PREVIOUS -> (deleted once safe)
                       \-> FAILED
```

Persisted in `MetadataStore`'s `dataset_versions` table (one small DuckDB file at
`<base-directory>/metadata/cache_metadata.duckdb`), which stores: `dataset_name`, `version`,
`state`, `file_path`, `table_name`, `row_count`, `bytes_loaded`, `started_at`, `completed_at`,
`duration_ms`, `source_sql_hash` (SHA-256 of the source SQL, so you can tell which source query
produced a given cache version), `validation_status`, `error_code`, `error_summary`, `created_at`,
`updated_at`.

## Refresh pipeline (`RefreshCoordinator.refresh`)

```
1. Look up dataset config; DISABLED short-circuits.
2. Acquire the dataset's RefreshLock (tryAcquire) -> ALREADY_RUNNING if another refresh is in flight.
3. Run the pipeline inside RetryExecutor, per the dataset's retry policy:
     a. Load source SQL, compute its SHA-256, allocate the next version number.
     b. Insert a BUILDING metadata row; build "<table>_v<N>_building.duckdb".
     c. Open a DremioSource stream; stream each Arrow batch straight into DuckDbDatasetWriter.
     d. finish() the writer (flush + CHECKPOINT); record row/byte counts; state -> VALIDATING.
     e. Run all configured validations (14-VALIDATION.md). Any failure -> ValidationFailedException.
     f. Rename "..._building.duckdb" -> "..._v<N>.duckdb"; record completion.
     g. VersionManager.activate(dataset, N): atomically N -> ACTIVE, old ACTIVE -> PREVIOUS,
        then clean up (or defer) any version now beyond the retained window.
4. Release the RefreshLock.
```

Every phase is timed independently (see [15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md)) and
returned in `DatasetRefreshResult.timings()`.

## Zero-downtime cutover

While step 3 runs, `financial_v10` stays ACTIVE and keeps serving every query. Only after
validation passes does `financial_v11` atomically become ACTIVE (a single DuckDB transaction
inside `MetadataStore.activateVersion`, demoting v10 to PREVIOUS in the same transaction). Queries
that started against v10 before the cutover keep running against v10 until they finish - see
"query version pinning" below.

## Retention: only ACTIVE + PREVIOUS

`data-cache.duckdb.max-versions` (must be >= 2) bounds how many completed versions are kept.
After every activation, `VersionManager` looks at PREVIOUS versions beyond `max-versions - 1` and
either deletes them immediately or, if a query still holds a reference, marks them
**pending deletion** - deleted the moment the last reader releases it. A version is never deleted
while `activeReaders > 0`; see `VersionManagerTest` for a real `CountDownLatch`-based concurrency
test of exactly this scenario.

## Query version pinning

`VersionManager.pinActive(datasetName)` increments an in-memory reader count for the dataset's
*current* ACTIVE version and returns a `VersionHandle`. This happens once per dataset at the very
start of `DuckDbQueryEngine.execute`, before any DuckDB work begins - so a query that pins v10
keeps its `VersionHandle` (and thus v10's physical file) alive even if v11 activates a moment
later. The handle is released in a `finally` block regardless of success or failure.

## Failure handling

If any phase throws, the BUILDING version is marked `FAILED` with an error code/summary, its
partial `..._building.duckdb` file is deleted, and **the currently ACTIVE version is never
touched**. `RefreshCoordinatorTest.failedRefreshDoesNotReplaceActiveVersion` and
`.validationFailureDoesNotActivateNewVersion` assert this directly.

## Retry

`RetryExecutor` retries only exceptions where `DataCacheException.isRetryable() == true`
(classified per exception type - see [04-DREMIO-CONFIGURATION.md](04-DREMIO-CONFIGURATION.md#error-classification)
for the Dremio-side classification). Configuration errors, bad SQL, unsupported types, and
validation failures are never retried. Backoff is exponential
(`initial-delay * multiplier^attempt`, capped at `max-delay`), driven through an injectable
`RetrySleeper` so tests never sleep in real wall-clock time.

## Concurrency bounds

- **Per dataset**: `RefreshLock` - a second concurrent `refresh("financial")` call while one is
  running returns `ALREADY_RUNNING` immediately, it does not queue or block.
- **Across datasets**: `DataCacheRefreshServiceImpl` runs every refresh (manual, scheduled, or
  part of `refreshAll()`) through one fixed-size `ExecutorService` sized by
  `data-cache.refresh.max-concurrent-datasets` - never an unbounded thread pool.

## Dynamic scheduling

`DynamicRefreshScheduler` registers one `CronTrigger` per enabled, cron-configured dataset against
a shared `ThreadPoolTaskScheduler`. There is no per-dataset `@Scheduled` method anywhere - adding
a dataset to configuration is sufficient to get its own independent schedule.

## Startup recovery

`StartupRecoveryService.recoverAll()` runs once, after the Spring context is fully ready
(`ApplicationReadyEvent`), before `DataCacheStartupCoordinator` evaluates each dataset's startup
mode and before the scheduler starts - see
[23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md) for the full startup sequence and
how it decides whether to auto-create, reuse, or background-refresh each dataset:

1. Any version left `BUILDING` or `VALIDATING` is a crash artifact (no reader could exist) -
   marked `FAILED`, physical file deleted.
2. If the ACTIVE version's physical file is missing, it is marked `FAILED` (the dataset then
   reports "no active version" instead of crashing the query engine).
3. A `PREVIOUS` version whose file is missing is simply forgotten (no in-flight readers can exist
   immediately after process start).
4. Any `*.duckdb` file with no matching metadata row is deleted as an orphan.
5. The retention policy is re-applied in case `max-versions` changed since the last run.

See `StartupRecoveryServiceTest` for direct tests of each rule, including the "V2 ACTIVE, V3
BUILDING -> V2 still ACTIVE after restart" scenario.

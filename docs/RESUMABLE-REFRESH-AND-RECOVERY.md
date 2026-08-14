# Resumable Refresh and Recovery

This document explains, in depth, how a large dataset refresh survives a crash, a pod restart, a
Dremio disconnect, or an intentional service stop - without restarting from zero, and without ever
risking a corrupted or duplicated cache. It is the reference for the `data-cache.resume.*` and
`data-cache.datasets.<name>.resume.*` configuration, the persistent checkpoint model, and the exact
guarantees the framework does and does not make.

If you only need the config knobs, jump to [Configuration reference](#13-configuration-reference). If
you want the worked example, see the Developer Guide's
["Resuming a large cache load after failure"](DATA-CACHE-DEVELOPER-GUIDE.md#24-resuming-a-large-cache-load-after-failure)
chapter, which walks through the exact 60M-row scenario this design targets.

## Contents

1. [Why a broken Arrow stream cannot simply be resumed](#1-why-a-broken-arrow-stream-cannot-simply-be-resumed)
2. [What a resume chunk is](#2-what-a-resume-chunk-is)
3. [What a completed checkpoint means](#3-what-a-completed-checkpoint-means)
4. [How checkpoint metadata is stored](#4-how-checkpoint-metadata-is-stored)
5. [What happens on crash](#5-what-happens-on-crash)
6. [What happens on restart](#6-what-happens-on-restart)
7. [What happens to the ACTIVE version](#7-what-happens-to-the-active-version)
8. [How duplicate prevention works](#8-how-duplicate-prevention-works)
9. [How source consistency is protected](#9-how-source-consistency-is-protected)
10. [When resume is refused](#10-when-resume-is-refused)
11. [Choosing a resume key and partition strategy](#11-choosing-a-resume-key-and-partition-strategy)
12. [Choosing a chunk size](#12-choosing-a-chunk-size)
13. [Configuration reference](#13-configuration-reference)
14. [Safe configuration examples](#14-safe-configuration-examples)
15. [Reading resume progress from logs](#15-reading-resume-progress-from-logs)
16. [Operational status](#16-operational-status)
17. [Admin API](#17-admin-api)
18. [Disk capacity during a resume](#18-disk-capacity-during-a-resume)
19. [When a full restart is necessary](#19-when-a-full-restart-is-necessary)

---

## 1. Why a broken Arrow stream cannot simply be resumed

Arrow Flight SQL streams are **disposable**. There is no supported way to reattach to a Flight
stream that broke mid-transfer and continue receiving batches where it left off - the connection,
the query execution on the Dremio side, and any client-side cursor state are all gone once the
stream fails. This is true whether the break was caused by a network blip, a Dremio-side timeout, a
JVM crash on the caching service, or the pod being killed.

Because of this, resumable refresh in this framework **never** attempts to resume a broken stream.
Every resume is a **new** `DremioSource.executeQuery(...)` call, issued fresh, against a **smaller,
bounded piece of the dataset** (a chunk - see below). The thing that survives a crash is not a
network connection or an in-memory cursor; it is a row of persisted metadata saying "chunk 41 of
120 already has its rows durably committed in DuckDB." Resume means "start new queries for the
chunks that don't have that row yet," never "reconnect and keep reading where the old stream
stopped."

This is also why resume is **not** implemented as `OFFSET`-based pagination over the source query.
`OFFSET` has no stable meaning without a guaranteed row order, is expensive for a source to
re-evaluate from position zero on every resume, and gives no natural unit to mark "done." Chunking
by a real, stable key (see [§11](#11-choosing-a-resume-key-and-partition-strategy)) avoids all three
problems.

## 2. What a resume chunk is

A resume chunk is a **bounded, independently loadable slice of the dataset**, defined by a
partition strategy (`RANGE`, `TIME_RANGE`, or `HASH_BUCKET` - see
[`resume/ResumePartitionStrategy.java`](../src/main/java/com/enterprise/datacache/feature/cache/resume/ResumePartitionStrategy.java)).
For the common `RANGE` case, a chunk is "rows where `transaction_id` is between 40,000,001 and
40,500,000" - a plain, safely-generated `WHERE` predicate over a stable numeric key, never a raw
row offset.

A chunk and an Arrow batch are **different things at different levels**:

- An **Arrow batch** is however many rows Dremio decides to put in one Flight message - typically
  tens of thousands of rows, driven by Dremio/Arrow internals, not something this framework
  controls.
- A **resume chunk** (default 500,000 rows, configurable) is made of **many** Arrow batches streamed
  one after another from a single `executeQuery` call for that chunk's `WHERE` predicate.

This distinction is what keeps checkpoint overhead low: the framework does **not** persist a
metadata row after every Arrow batch (that would mean tens of thousands of DuckDB transactions for
a 60M-row load). It persists one chunk-completion row after each chunk - a few dozen to a couple of
hundred metadata writes for the whole refresh, not tens of thousands.

## 3. What a completed checkpoint means

**The durable checkpoint is: a chunk marked `COMPLETED` in persisted metadata. It is never "the last
row observed from Arrow," and never an in-memory counter.**

Concretely, a chunk is `COMPLETED` only after, in this order:

1. Every Arrow batch for the chunk's query has been streamed and appended to the BUILDING DuckDB
   file's staging table (via `DuckDBAppender`).
2. The Appender has been flushed and closed.
3. `connection.commit()` has returned successfully on the DuckDB JDBC connection - this is the
   actual durability boundary; DuckDB's WAL/commit semantics guarantee those rows survive a crash
   from this point on.
4. The chunk's completion (`status=COMPLETED`, `rowsLoaded`, `completedAt`) has been written to the
   metadata store (a separate small DuckDB file - see [§4](#4-how-checkpoint-metadata-is-stored)).

A chunk that is `RUNNING` when the process dies - whether zero rows were streamed, some batches
were streamed but the transaction never committed, or (the narrowest window) the transaction
committed but step 4 above never ran - is **always** treated as incomplete and safely retried. See
[§8](#8-how-duplicate-prevention-works) for why retrying never duplicates rows even in that last,
narrowest case.

**`rowsRead` vs. `rowsCommitted`**: the framework tracks these as two distinct numbers throughout a
refresh. `rowsRead` increases as Arrow batches are streamed - it is provisional, in-flight,
not-yet-durable progress. `rowsCommitted` only increases when a chunk reaches the durability
boundary in step 3 above. Status APIs and operational logs report `rowsCommitted` as the number
that means anything for "how much of this refresh can survive a crash right now" - `rowsRead` is
shown alongside it (only when it differs) purely as a liveness signal that the current chunk is
actively streaming, never as a substitute for durable progress.

## 4. How checkpoint metadata is stored

All resumable-refresh metadata lives in the **same** small metadata DuckDB file
(`<base-directory>/metadata/cache_metadata.duckdb`) that already tracks dataset versions - not in
the (potentially huge) BUILDING dataset file itself, and not only in memory. Two tables, managed by
`MetadataStore`:

- **`dataset_refresh_manifest`** - one row per BUILDING attempt (`dataset_name`, `version`): tracks
  `refresh_id`, `source_sql_hash`, `source_schema_hash`, `resume_enabled`, `resume_strategy`,
  `partition_column`, `chunk_size`, `source_snapshot_id`, `consistency_mode`, `total_chunks`,
  `completed_chunks`, `failed_chunks`, `rows_committed`, `status` (`BUILDING` / `COMPLETED` /
  `ABANDONED` / `FAILED`), `started_at`, `last_updated_at`.
- **`dataset_refresh_chunk`** - one row per chunk (`dataset_name`, `version`, `chunk_id`):
  `partition_start`, `partition_end`, `status` (`PENDING` / `RUNNING` / `COMPLETED` / `FAILED`),
  `rows_loaded`, `started_at`, `completed_at`, `attempt_count`, `error_code`, `error_summary`.

Restart recovery reconstructs "what's left to load" **entirely** from these two tables (see
[§6](#6-what-happens-on-restart)) - never from any in-memory progress counter, which by definition
does not survive a crash.

## 5. What happens on crash

Regardless of *where* in a chunk's lifecycle the crash lands, the invariant is the same: **nothing
after the last chunk's DuckDB `commit()` is trusted**, and **everything up to and including it is**.

| Crash point | State left behind | What retry does |
|---|---|---|
| Before any row of chunk N streamed | Chunk N still `PENDING`/`RUNNING`, zero rows for chunk N in the BUILDING file | Re-run chunk N's query from scratch |
| Partway through streaming chunk N (some Arrow batches appended, `commit()` never called) | The uncommitted rows are gone (never durable); chunk N still `RUNNING` | Re-run chunk N's query from scratch - nothing to clean up, the partial rows were never committed |
| After chunk N's `commit()`, before its metadata row is written | Chunk N's rows **are** durably in the BUILDING file; metadata still shows `RUNNING`/`PENDING` | Re-run chunk N's query - see [§8](#8-how-duplicate-prevention-works) for why this does not duplicate rows |
| Between two chunks (chunk N `COMPLETED`, chunk N+1 not started) | Chunk N safely `COMPLETED`; chunk N+1 `PENDING` | Skip chunk N, start chunk N+1 |
| During final validation/activation (all chunks `COMPLETED`, manifest `COMPLETED`) | The BUILDING file has all rows; the `dataset_versions` row is stuck `VALIDATING` | Re-validates on the next attempt (see [§19](#19-when-a-full-restart-is-necessary)) |

The current ACTIVE version is **never** touched by any of this - see [§7](#7-what-happens-to-the-active-version).

## 6. What happens on restart

On Spring Boot startup, in order:

1. **`StartupRecoveryService`** reconciles metadata against physical storage for every dataset.
   Any version left `BUILDING` is evaluated for safe resumability (see the table below); any version
   left `VALIDATING` is always treated as an interrupted crash artifact and discarded (its load
   already finished, so a fresh re-load is simple and the window is short - re-validating is not
   itself made resumable). Versions found safely resumable are **left alone** - not marked `FAILED`,
   not deleted.
2. **`DataCacheStartupCoordinator`** decides, per dataset, whether an initial load, a background
   refresh, or nothing is needed - and if a resumable BUILDING manifest survived step 1, triggers a
   refresh for it even under `StartupMode.USE_EXISTING_OR_CREATE` (which would otherwise do
   nothing when ACTIVE already exists), because automatic resume must never require manual
   intervention.
3. That refresh call reaches **`ResumableRefreshExecutor`**, which re-derives "what's left" purely
   from the `dataset_refresh_manifest`/`dataset_refresh_chunk` tables: any chunk still `RUNNING`
   (a crash artifact - nothing can genuinely still be running right after process start) is reset to
   `PENDING`, then chunks are processed in order, skipping every `COMPLETED` one.

`StartupRecoveryService`'s resumability check for a BUILDING version, in order:

1. Is `data-cache.resume.enabled` (global) and `data-cache.datasets.<name>.resume.enabled` both
   true? If not, this dataset never resumes - fall through to the original behavior (mark `FAILED`,
   delete the file).
2. Does a `dataset_refresh_manifest` row exist for this `(dataset, version)`, with
   `status=BUILDING` and `resume_enabled=true`? If not, not resumable.
3. Is the manifest within `data-cache.resume.max-resume-age` of `started_at`? If not, mark the
   manifest `ABANDONED` and fall through to the original behavior.
4. Does the physical `.duckdb` file exist and open cleanly (`SELECT 1` succeeds against it)? If
   not, mark the manifest `FAILED` and fall through.
5. Otherwise: **preserve it.** Log `event=startup-recovery-resumable-build-preserved` and leave
   both the `dataset_versions` row and the manifest exactly as they were.

The **expensive** compatibility checks - source SQL hash and source schema hash - are deliberately
**not** run here, because they would require a live Dremio call during startup recovery for every
dataset, even ones nobody is about to refresh yet. They run lazily, the moment an actual resume
attempt starts (see [§10](#10-when-resume-is-refused)).

## 7. What happens to the ACTIVE version

**The ACTIVE version is never touched while a BUILDING version resumes.** Every code path that
resumes a chunk operates exclusively on the BUILDING file (`<table>_v<N>_building.duckdb`); the
ACTIVE file (`<table>_v<M>.duckdb`, `M` = whatever was last successfully activated) is a completely
separate physical file, opened read-only by the query engine, and is only ever replaced by
`VersionManager.activate(...)` - which resumable refresh reaches through the exact same
validate-then-activate path as a normal (non-resumed) refresh, only after **every** chunk of the
new version is `COMPLETED` and validation passes.

Concretely: while dataset `financial` version 13 is resuming from chunk 41/120, every query against
`financial` keeps reading version 12 - the query engine has no notion of "resuming," it only ever
sees "the current ACTIVE version," which does not change until 13 finishes and activates. This is
proven by an automated test (`ResumableRefreshEndToEndTest#activeVersionKeepsServingQueriesWhilePausedThenSwitchesOnlyOnceResumeCompletes`),
not just asserted here.

## 8. How duplicate prevention works

**Mandatory rule: whether a chunk is executed once, twice, or five times because of retries, the
final cache contains that chunk's rows exactly once.**

The mechanism (`ResumableChunkWriter`, "Option A" from the design space - an internal chunk-id
column, not one physical staging table per chunk, which would not scale to hundreds of chunks):

1. The BUILDING table is created with one extra, internal column, `__cache_chunk_id BIGINT NOT
   NULL`, alongside the real business columns.
2. Before (re-)loading chunk N, the writer runs `DELETE FROM <table> WHERE __cache_chunk_id = N`
   inside the same transaction it is about to append to. On a genuinely fresh chunk this deletes
   zero rows; on a retry (whether the previous attempt never committed, or committed but crashed
   before its metadata was recorded) this removes exactly and only that chunk's previous rows.
3. Every row appended for chunk N carries `__cache_chunk_id = N`.
4. The DELETE and the re-INSERT commit together, atomically, in one DuckDB transaction
   (`connection.setAutoCommit(false)` + explicit `connection.commit()`) - there is no window where
   chunk N's table state is "half old, half new."
5. Once every chunk is `COMPLETED`, `finalizeTable()` runs `ALTER TABLE ... DROP COLUMN
   __cache_chunk_id` and a `CHECKPOINT` - the internal column is never exposed to validation or to
   analytical queries.

This is exercised directly by `ResumableRefreshExecutorTest#sameChunkExecutedMultipleTimesLeavesExactlyOneCopyOfItsRows`,
which runs the same chunk through the writer five times and asserts the row count never grows past
one copy, and indirectly by every crash-simulation test in that file (final row counts and
`COUNT(*) == COUNT(DISTINCT id)` are asserted after every simulated crash+retry).

## 9. How source consistency is protected

**This is the most important correctness property in the whole design.** A resumed refresh must
never silently combine "the first 20M rows read from yesterday's source state" with "the remaining
40M rows read from today's changed source state" unless that is explicitly acceptable for the
dataset.

Two knobs control this, both per dataset (`data-cache.datasets.<name>.resume.*`):

- **`consistency`**: `STRICT_SNAPSHOT` (default) or `BEST_EFFORT`.
  - `STRICT_SNAPSHOT` requires a snapshot binding (below) to even start building - config validation
    fails at startup otherwise (see [§10](#10-when-resume-is-refused)). This is the recommended and
    default setting for financial data: **a correct 60M-row cache is more important than saving 20M
    rows of previous load work.** If the source cannot guarantee a consistent resumed read, resume
    is refused and a fresh version is built instead - the previous partial work is abandoned, never
    silently mixed in.
  - `BEST_EFFORT` skips the snapshot requirement, for sources/datasets where a small amount of drift
    between the start and end of a long load is known to be acceptable (e.g. slowly-changing
    reference data). Choose this deliberately, not as a default.

- **`snapshot.mode`**: `NONE` (default) or `AS_OF_VALUE`.
  - `AS_OF_VALUE` captures one `Instant` (`Instant.now()`) exactly once, when a **new** BUILDING
    version is first created (never re-captured on a resume of the same version), stores it as
    `source_snapshot_id` in the manifest, and binds that **same** value into **every** chunk's query
    for the life of that version - including chunks executed after a restart. Binding is a safe,
    framework-controlled literal substitution: the token `:<snapshot.parameter-name>` in your source
    SQL is replaced with a fixed-format `TIMESTAMP '...'` literal built from the captured `Instant`
    via a pinned `DateTimeFormatter` - never string-concatenated from anything external.

  Your source SQL must reference the parameter for this to do anything:

  ```sql
  SELECT transaction_id, amount, business_date
  FROM financial_transactions
  WHERE ingestion_timestamp <= :cacheAsOf
  ```

  with

  ```yaml
  data-cache:
    datasets:
      financial:
        resume:
          consistency: STRICT_SNAPSHOT
          snapshot:
            mode: AS_OF_VALUE
            parameter-name: cacheAsOf
  ```

This framework deliberately does **not** invent Dremio- or Iceberg-specific snapshot/version-pinning
syntax (e.g. Iceberg `VERSION AS OF`) - that would require verifying exact syntax against a live
Dremio/Iceberg setup this project does not have access to. The `AS_OF_VALUE` mechanism above is the
verified, honest alternative: it works with any source that has (or can be given, via a view) an
ingestion/business timestamp column to filter on. If your source exposes a native snapshot/version
identifier and you have verified the exact syntax for your Dremio version, you can reference it
directly in your `source-sql` and treat that as your `AS_OF_VALUE` binding target - the framework's
job is only to capture *some* stable value once and bind it consistently into every chunk, not to
know what that value means to your source.

## 10. When resume is refused

Resume is refused - the partial BUILDING version is marked `ABANDONED`, a **fresh** version is
created and built from zero, and the current ACTIVE version is preserved throughout - whenever
safety cannot be proven:

| Condition | Detected | Result |
|---|---|---|
| `data-cache.resume.enabled=false`, or the dataset's `resume.enabled=false` | Every refresh attempt | No resume is ever attempted; a failed BUILDING version restarts from zero, same as before this feature existed |
| Source SQL text changed since the BUILDING version started | `Sha256` hash comparison, cheap, no Dremio call, checked before any resume decision | Old version `ABANDONED`, new version created |
| Resume strategy / partition column / chunk size changed | Manifest vs. current config comparison, cheap | Old version `ABANDONED`, new version created |
| BUILDING version older than `data-cache.resume.max-resume-age` | Manifest `started_at` vs. now | Old version `ABANDONED`, new version created |
| Source Arrow schema differs from the schema recorded for this BUILDING version (e.g. `DECIMAL(18,3)` -> `DECIMAL(38,9)`) | Compared once per chunk actually processed, against the hash recorded from the first chunk of the version | Old version `ABANDONED`, new version created - **never** blindly appended into the old partial table |
| BUILDING file missing or fails to open (`SELECT 1`) | Startup recovery, cheap | Marked `FAILED`/`ABANDONED`, new version created on next attempt |
| `consistency=STRICT_SNAPSHOT` without a configured `snapshot.mode: AS_OF_VALUE` | **Config validation at application startup** | Application **fails to start** with a clear error - this is a fail-fast, not a silent runtime fallback |

The last row matters: this framework will not let you accidentally end up with an unsafe resumable
configuration. If `resume.enabled=true` but no valid strategy or key is configured, or
`consistency=STRICT_SNAPSHOT` has no snapshot binding, `DataCacheConfigurationValidator` collects a
clear message (e.g. *"`data-cache.datasets.financial.resume.consistency=STRICT_SNAPSHOT` (the
default) requires `resume.snapshot.mode: AS_OF_VALUE` with a `snapshot.parameter-name` your source
SQL references... Configure `snapshot.mode: AS_OF_VALUE`, or explicitly accept the risk with
`consistency: BEST_EFFORT`."*) and refuses to start the application at all, rather than silently
running with an unsafe resume path.

## 11. Choosing a resume key and partition strategy

A dataset can only resume safely if it has a **deterministic, stable** partition/resume key.

**Good keys:**
- A stable, monotonically increasing (or at least bounded and stable) numeric ID - a real primary
  key like `transaction_id` (`RANGE`).
- A business date or timestamp column with a natural, evenly-distributed partitioning
  (`TIME_RANGE`).
- A stable hash of a business key that does not change between reads (`HASH_BUCKET`).
- A source snapshot/batch identifier that is itself deterministic across re-reads.

**Bad keys - do not configure resume for a dataset that only has these:**
- Arbitrary query result order with no `ORDER BY` guarantee.
- `OFFSET`-based pagination without a stable `ORDER BY` (the framework does not offer this as an
  option at all - see [§1](#1-why-a-broken-arrow-stream-cannot-simply-be-resumed)).
- A non-deterministic `ROW_NUMBER()` (e.g. one whose `ORDER BY` ties are broken differently on each
  evaluation).
- A volatile source with no snapshot/version capability and no acceptable drift (i.e. it needs
  `STRICT_SNAPSHOT` but cannot supply one).

If a dataset does not have a deterministic key, leave `resume.enabled: false` (the default) for it.
It will restart its BUILDING version from zero after a failed/interrupted load, exactly as this
framework behaved before resumable refresh existed - which is honest and safe, just not resumable.

### The three strategies

| Strategy | Use for | How chunks are planned | How a chunk's `WHERE` is built |
|---|---|---|---|
| `RANGE` | A stable, numeric, roughly-monotonic key | One `MIN`/`MAX` probe query against your `source-sql`, then contiguous ranges of `chunk-size` values | `<column> >= <start> AND <column> <= <end>`, both bounds framework-computed `Long` values formatted via `Long.toString` - never string-concatenated from external input |
| `TIME_RANGE` | A date/timestamp column | Same `MIN`/`MAX` probe, then one chunk per `interval` (e.g. `1d`) | `<column> >= TIMESTAMP '<start>' AND <column> <= TIMESTAMP '<end>'`, both bounds inclusive and formatted via a fixed `DateTimeFormatter` |
| `HASH_BUCKET` | A stable key with no natural range (e.g. a string ID) | `hash-buckets` chunks, numbered `0..N-1`, no probe query needed | `(<hash-expression>) = <bucket>` - `hash-expression` is **entirely dataset-owner-supplied, privileged configuration**, never invented or verified by the framework (Dremio-specific hash syntax cannot be assumed without a live cluster to verify against); still checked for obviously unsafe fragments (`;`, `--`, `/*`, `*/`) as defense in depth |

All three read chunk bounds only from framework-computed, strongly-typed values (a probed `Long`/
`Instant`, or a plain loop counter) - never from raw external/user-provided text. `partition-column`
identifiers are validated against a strict `^[A-Za-z_][A-Za-z0-9_]*$` pattern at config-validation
time, so there is no SQL-injection surface through dataset configuration either (dataset config is
privileged, but identifiers are still validated).

## 12. Choosing a chunk size

`chunk-size` (rows per `RANGE` chunk; also the probe granularity) trades off two things:

- **Smaller chunks** (e.g. 250,000 rows): more frequent checkpoints, so a crash loses less
  in-flight work; more transaction/metadata overhead (more `commit()` + metadata-write pairs for
  the same total row count).
- **Larger chunks** (e.g. 1,000,000 rows): less overhead, higher sustained throughput; more rows
  potentially re-streamed if a chunk fails partway through (though never duplicated in the final
  table - see [§8](#8-how-duplicate-prevention-works)).

There is no single universal correct number - the default is `500,000`, a reasonable midpoint for a
multi-million-row dataset. Benchmark with your actual source and network characteristics (see
[15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md) for the underlying DuckDB write-throughput
methodology) across 250K/500K/1M and pick based on:

- How much work you're comfortable re-doing after a typical failure (smaller = less).
- How much your Dremio source is bothered by many small `WHERE`-bounded queries vs. fewer large
  ones (larger chunks mean fewer, bigger queries).
- Total load time sensitivity - per-chunk overhead is transaction/metadata cost, not proportional to
  chunk size, so very small chunks (e.g. 10,000) measurably hurt throughput for a 60M-row load.

`TIME_RANGE`'s `interval` (e.g. `1d`, `6h`) plays the same role - pick a width that gives a
reasonable number of chunks for your typical `MIN`/`MAX` span.

## 13. Configuration reference

### Global (`data-cache.resume.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. `false` makes every dataset behave as though its own `resume.enabled` were `false`, regardless of per-dataset config. |
| `max-resume-age` | `24h` | A BUILDING version older than this is not resumed - it is abandoned and a fresh version started. Must be positive. |
| `on-incompatible-source` | `RESTART_NEW_VERSION` | What to do when SQL/schema incompatibility is detected (currently the only supported value: abandon and start fresh, never blindly continue). |

### Per dataset (`data-cache.datasets.<name>.resume.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Opt-in. Requires the dataset owner to know a genuinely stable resume key exists (see [§11](#11-choosing-a-resume-key-and-partition-strategy)). |
| `strategy` | *(none)* | `RANGE`, `TIME_RANGE`, or `HASH_BUCKET`. Required when `enabled: true` - config validation fails at startup otherwise. |
| `partition-column` | *(none)* | Required for `RANGE`/`TIME_RANGE`. Must be a plain SQL identifier. |
| `chunk-size` | `500000` | Target rows per chunk for `RANGE`; also its probe granularity. |
| `interval` | *(none)* | Chunk width for `TIME_RANGE` (e.g. `1d`). Required when `strategy: TIME_RANGE`. |
| `hash-buckets` | `128` | Number of buckets for `HASH_BUCKET`. |
| `hash-expression` | *(none)* | Required for `HASH_BUCKET`. Trusted, dataset-owner-supplied SQL expression producing an integer in `[0, hash-buckets)`. |
| `snapshot.mode` | `NONE` | `NONE` or `AS_OF_VALUE`. |
| `snapshot.parameter-name` | *(none)* | Required when `snapshot.mode: AS_OF_VALUE`; the `:name` token your `source-sql` references. |
| `consistency` | `STRICT_SNAPSHOT` | `STRICT_SNAPSHOT` (requires a snapshot binding) or `BEST_EFFORT`. |

## 14. Safe configuration examples

### RANGE - a stable numeric transaction ID

```yaml
data-cache:
  resume:
    enabled: true
    max-resume-age: 24h
  datasets:
    financial:
      source-sql: classpath:datacache/dremio/financial.sql
      resume:
        enabled: true
        strategy: RANGE
        partition-column: transaction_id
        chunk-size: 500000
        consistency: STRICT_SNAPSHOT
        snapshot:
          mode: AS_OF_VALUE
          parameter-name: cacheAsOf
```

```sql
-- classpath:datacache/dremio/financial.sql
SELECT transaction_id, cost_center, fiscal_year, amount, ingestion_timestamp
FROM financial_transactions
WHERE ingestion_timestamp <= :cacheAsOf
```

### TIME_RANGE - a business date column

```yaml
data-cache:
  datasets:
    headcount:
      source-sql: classpath:datacache/dremio/headcount.sql
      resume:
        enabled: true
        strategy: TIME_RANGE
        partition-column: business_date
        interval: 1d
        consistency: STRICT_SNAPSHOT
        snapshot:
          mode: AS_OF_VALUE
          parameter-name: cacheAsOf
```

### HASH_BUCKET - a stable string key with no natural range

```yaml
data-cache:
  datasets:
    organization:
      source-sql: classpath:datacache/dremio/organization.sql
      resume:
        enabled: true
        strategy: HASH_BUCKET
        hash-expression: "MOD(HASH(cost_center), 64)"
        hash-buckets: 64
        consistency: BEST_EFFORT # organization reference data changes rarely; drift during a load is acceptable here
```

`hash-expression` is **not verified by the framework** - it must be valid, deterministic SQL for
your actual Dremio version, tested against a live cluster before you rely on it. Getting this wrong
does not corrupt data (each bucket is still an independent, idempotent chunk) but a
non-deterministic expression could cause the same logical rows to land in a different bucket on
different reads, which defeats resumability - verify determinism yourself for your source.

### Opting a dataset out of resume entirely

```yaml
data-cache:
  datasets:
    scratch_report:
      source-sql: classpath:datacache/dremio/scratch_report.sql
      resume:
        enabled: false # default; explicit here for clarity - restarts from zero after any interruption
```

## 15. Reading resume progress from logs

Every resumable-refresh event is a structured, grep-able INFO/WARN line, following the same
`event=... key=value ...` convention as the rest of the framework's logging (see
[17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md)). The exact sequence for a refresh that
gets interrupted and later resumed:

```
# Before the crash - normal chunk progress, exactly like a non-resumable refresh's progress lines,
# with resume-specific fields layered on:
event=dataset-load-progress dataset=financial version=13 status=BUILDING rowsProcessed=20000000 \
    elapsedMs=184213 averageRowsPerSecond=108547.2 currentRowsPerSecond=111203.4 \
    resume=false rowsCommitted=20000000 completedChunks=40 totalChunks=120

# ... process crashes here ...

# On restart, before the first chunk resumes:
event=startup-recovery-resumable-build-preserved dataset=financial version=13 completedChunks=40 \
    totalChunks=120 rowsCommitted=20000000 refreshId=...

event=dataset-refresh-resuming dataset=financial version=13 completedChunks=40 totalChunks=120 \
    rowsCommitted=20000000 nextChunk=41

# Progress continues from where it left off - completedChunks/rowsCommitted pick up at 40/20M,
# never reset to zero:
event=dataset-load-progress dataset=financial version=13 status=BUILDING rowsProcessed=20500000 \
    resume=true rowsCommitted=20500000 completedChunks=41 totalChunks=120
```

`rowsRead` only appears in a progress line when it differs from `rowsCommitted` - i.e. while a
chunk is actively streaming but has not yet committed (see [§3](#3-what-a-completed-checkpoint-means)).

A failed-but-resumable pause looks like this:

```
event=dataset-refresh-failed dataset=financial failedVersion=13 existingActiveVersion=12 \
    existingActivePreserved=true rowsCommitted=20000000 completedChunks=40 totalChunks=120 \
    failedChunk=41 elapsedMs=184213 stage=BUILDING status=PAUSED_RETRYABLE \
    errorCode=DREMIO_SOURCE_ERROR error=...
```

`status=PAUSED_RETRYABLE` here means exactly what it says: if the service stays alive, its normal
retry policy may retry chunk 41; if the service stops, the next startup's restart-recovery sequence
above continues from chunk 41 - not from zero, and not by discarding the 40 already-completed
chunks.

## 16. Operational status

`GET /api/v1/cache/admin/datasets/{name}` (and the `DataCacheStatusService` Java API) exposes, in
addition to the fields already documented in [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md)
and [18-OPERATIONS.md](18-OPERATIONS.md):

```json
{
  "datasetName": "financial",
  "activeVersion": 12,
  "buildingVersion": 13,
  "refreshInProgress": false,
  "resumable": true,
  "resuming": null,
  "completedChunks": 40,
  "totalChunks": 120,
  "rowsCommitted": 20000000,
  "currentChunk": null,
  "failedChunk": 41,
  "sourceSnapshotId": "2026-08-14T10:15:30Z",
  "pausedRetryable": true,
  "lastRefreshStatus": "PAUSED_RETRYABLE"
}
```

- `resumable` - whether this dataset's configuration has resume enabled at all (a static config
  fact, always present).
- `resuming` - `true` only while a currently in-flight refresh is itself continuing a previous
  partial build; `null` when nothing is in flight.
- `completedChunks` / `totalChunks` / `rowsCommitted` - read live from `RefreshProgress` while a
  refresh is in flight, or from the persisted `dataset_refresh_manifest` row when a BUILDING version
  is sitting paused with nothing currently running - so this is meaningful whether or not a refresh
  is active right now.
- `currentChunk` - the chunk id currently being loaded; only set while a refresh is actually running.
- `failedChunk` - the chunk id that most recently failed (either from the live attempt, or read
  from persisted chunk state for a paused build); cleared once that chunk completes.
- `sourceSnapshotId` - the captured `AS_OF_VALUE` snapshot binding, if configured, so operators can
  see exactly what point-in-time a paused or completed BUILDING version is reading from.
- `pausedRetryable` - `true` when a resumable BUILDING manifest exists but nothing is currently
  processing it (the operational signal that this dataset needs either its next automatic retry, an
  automatic startup resume, or a manual `POST .../resume` call - never that it needs manual DB
  surgery).

No sensitive business data is ever exposed by this endpoint - only counts, ids, timestamps, and
status enums.

## 17. Admin API

- `POST /api/v1/cache/admin/datasets/{name}/refresh` - the general-purpose refresh trigger; for a
  resumable dataset with an existing compatible BUILDING manifest, this **already** resumes it
  rather than starting fresh (the same decision `ResumableRefreshExecutor` makes automatically).
- `POST /api/v1/cache/admin/datasets/{name}/resume` - an explicit resume request. Behaves exactly
  like `/refresh` except it first checks that a resumable BUILDING manifest actually exists; if not,
  it returns immediately with `outcome=NO_RESUMABLE_BUILD` rather than accidentally starting a
  brand-new full load when an operator specifically asked to "resume."
- `POST /api/v1/cache/admin/datasets/{name}/restart-refresh` - **protected admin operation.**
  Intentionally abandons any partial BUILDING version (marks its manifest `ABANDONED`, its
  `dataset_versions` row `FAILED`, deletes its physical file) and starts a brand-new version from
  zero, even if the partial build would otherwise have been safely resumable. The current ACTIVE
  version is never touched. Use this only when a partial build is known to be undesirable to
  continue - e.g. it was built against a source state now known to be wrong.

**Automatic startup resume is mandatory and does not depend on any of these being called** - see
[§6](#6-what-happens-on-restart). These endpoints exist for operator visibility/control, not because
resume ever requires manual intervention to happen at all.

## 18. Disk capacity during a resume

Because a partial BUILDING cache now **persists across a restart** instead of being deleted, it is
possible - for however long a resume takes - to have **three** physical generations of a dataset's
file on disk simultaneously:

1. **ACTIVE** - currently serving queries.
2. **PREVIOUS** - the prior ACTIVE version, retained briefly for in-flight query safety (see
   [06-DUCKDB-CACHE.md](06-DUCKDB-CACHE.md)).
3. **BUILDING** - the resuming version, growing chunk by chunk.

Capacity planning must account for **all three at once**, not just ACTIVE + BUILDING as before this
feature. Size your persistent volume for at least `3 x` the largest dataset's expected on-disk size,
not `2x`, if that dataset has resume enabled - see
[16-MEMORY-SIZING.md](16-MEMORY-SIZING.md) and [20-OPENSHIFT-DEPLOYMENT.md](20-OPENSHIFT-DEPLOYMENT.md)
for the full sizing/PVC guidance this adds to.

## 19. When a full restart is necessary

A resumable dataset restarts from zero (abandons its partial BUILDING version, builds fresh) rather
than resuming when:

- Source SQL changed (§10).
- Source schema changed incompatibly (§10) - including a decimal precision/scale widening like
  `DECIMAL(18,3)` -> `DECIMAL(38,9)` (see the decimal schema evolution policy in
  [14-VALIDATION.md](14-VALIDATION.md) for how a schema *narrowing* is handled differently at
  activation time - that policy is orthogonal to this one, which only concerns mid-build detection).
- The source snapshot the partial build was reading from is no longer available/valid.
- The resume key configuration (`strategy`/`partition-column`/`chunk-size`/`interval`) changed.
- The BUILDING version exceeded `max-resume-age`.
- The BUILDING file is missing or fails to open.
- `resume.enabled` was turned off for the dataset (or globally) between attempts.
- An operator explicitly calls `POST .../restart-refresh`.

In every one of these cases, the current ACTIVE version keeps serving queries throughout, and no
step ever produces "half old source state, half new source state" or "half old schema, half new
schema" inside one physical file - correctness is always chosen over preserving partial work.

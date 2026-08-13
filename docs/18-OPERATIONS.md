# 18. Operations

## Multi-replica warning

**`RefreshLock`, `VersionManager`, and `MetadataStore` coordinate only within a single JVM
process.** Running multiple replicas of the application (whether the standalone demo app or a host
service embedding `feature.cache`) that each point at the same shared
`data-cache.duckdb.base-directory` and each independently trigger refreshes is **not safe** - two
replicas could both decide to build "version 12" simultaneously, race on file writes, or disagree
about which version is ACTIVE.

The initial design assumes **one refresh-owning replica**. Practical deployment options:

1. Run a single replica for refresh + query serving (simplest; fine for most workloads given
   DuckDB's read performance).
2. Run one dedicated "refresher" replica (cron/admin-triggered refreshes) and N read-only replicas
   that only serve queries against the same shared, read-only-mounted storage. Note that the
   mandatory startup auto-create rule (see
   [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md)) fires on **every** replica for
   any dataset with no ACTIVE version - there is no per-replica opt-out - so start the refresher
   replica first and let it produce at least one ACTIVE version per dataset before starting the
   read-only replicas, or they will attempt (and duplicate) their own initial loads too. Once every
   dataset has an ACTIVE version, a read replica's `VersionManager` only learns about new versions
   by re-reading `MetadataStore` - which it does on every `pinActive()` call - so this works as
   long as only the refresher replica ever *writes* new versions afterward.
3. Introduce an external distributed lock (e.g. a Kubernetes `Lease`, a database advisory lock) in
   front of `RefreshLock` if you need multiple replicas that can all trigger refreshes safely. This
   was deliberately **not implemented** - `RefreshLock`, `VersionManager`, and the metadata
   `Storage` boundary are kept clean specifically so a distributed implementation can be swapped in
   later without redesigning the refresh pipeline.

## Runbook

| Symptom | Likely cause | Action |
|---|---|---|
| `GET /admin/datasets/{name}` shows `lastRefreshStatus: FAILED` | See `lastError` | Check `errorCode`; retryable errors already retried per policy - see [19-TROUBLESHOOTING.md](19-TROUBLESHOOTING.md) |
| `refreshInProgress: true` for an unexpectedly long time | Slow Dremio source query, or a real hang | Check `/actuator/metrics/datacache.refresh.phase.duration` to see which phase is slow |
| `activeVersion: null` | Never successfully refreshed, or startup recovery marked the ACTIVE file `FAILED` (missing file) | Trigger a manual refresh |
| Disk filling up under `duckdb.base-directory` | `max-versions` too high, or refreshes failing before cleanup runs | Lower `max-versions`; check for `event=version-cleanup-deferred` logs indicating stuck readers |
| Query 500s intermittently right after a refresh completes | Should not happen - version pinning is designed to prevent this; file a bug with the query name and dataset versions involved | - |

## Graceful shutdown

- `spring.lifecycle.timeout-per-shutdown-phase` + `server.shutdown: graceful` (set in
  `application.yml`) stop new HTTP requests from being accepted while in-flight ones complete.
- `DataCacheRefreshServiceImpl`'s executor is shut down via `@Bean(destroyMethod = "shutdown")` -
  no new refreshes start during shutdown; in-flight ones are not forcibly interrupted.
- `DynamicRefreshScheduler` is stopped via `destroyMethod = "stop"`, cancelling pending cron
  triggers.
- `DremioSource` and the shared Arrow `BufferAllocator` are closed via `destroyMethod = "close"`.
- `MetadataStore`'s DuckDB connection is closed via `destroyMethod = "close"`.
- **ACTIVE caches are never deleted on shutdown** - only `VersionManager`'s normal
  retention/cleanup path deletes files, and only when safe (zero readers, beyond the retained
  window).

## Backups

DuckDB files under `duckdb.base-directory` are ordinary files on the mounted PVC - back them up
with your normal volume snapshot tooling. The metadata database
(`<base-directory>/metadata/cache_metadata.duckdb`) should be backed up alongside the dataset
files so version history stays consistent with the physical files it describes.

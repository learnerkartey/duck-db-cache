# 23. Startup Cache Lifecycle

This document is the authoritative reference for what happens to every dataset's cache when the
application (or an embedding host application) starts. It matches the actual implementation in
`com.enterprise.datacache.refresh.DataCacheStartupCoordinator`,
`com.enterprise.datacache.config.StartupMode`/`StartupExecutionMode`, and
`com.enterprise.datacache.health.DataCacheReadinessIndicator` exactly - see the mandatory startup
tests in `DataCacheStartupCoordinatorTest`, `DataCacheReadinessIndicatorTest`, and
`EmbeddingAcceptanceTest` for automated proof of every behavior described below.

## The one rule that always applies

**If a dataset has no ACTIVE version, the application automatically loads one on startup - for
every startup mode, with no configuration needed to opt in, and no manual API call required.** A
dataset is never left permanently empty just because nobody has triggered a refresh yet.

Everything else described in this document only matters for the case where an ACTIVE version
**already exists** (typically: the application restarted).

## `StartupMode` (per dataset)

```java
public enum StartupMode {
    USE_EXISTING_OR_CREATE,
    ALWAYS_REFRESH
}
```

Configured per dataset: `data-cache.datasets.<name>.startup.mode` (default `USE_EXISTING_OR_CREATE`).

### Why only two modes

An earlier design considered three modes - `REUSE_EXISTING`, `REFRESH_IF_MISSING`,
`ALWAYS_REFRESH` - mirroring a common "reuse / create-if-missing / always-refresh" pattern. Once
the mandatory auto-create rule above is applied uniformly, `REUSE_EXISTING` and
`REFRESH_IF_MISSING` become **exactly the same behavior**: both reuse an existing ACTIVE version
without refreshing, and both auto-create when none exists. Keeping both names would only give
operators two different words for one behavior, so they are collapsed into
`USE_EXISTING_OR_CREATE`. `ALWAYS_REFRESH` remains distinct because it does something extra
(build a new version in the background) when a cache already exists.

### `USE_EXISTING_OR_CREATE` (default)

| Existing ACTIVE version? | Behavior |
|---|---|
| Yes | Reused immediately. **No refresh is triggered just because the application (re)started.** The dataset's configured `refresh-cron` (or a manual/admin trigger) refreshes it later, on its own schedule. |
| No | An initial load is triggered automatically (the mandatory rule above) - a real refresh through the normal pipeline: Dremio -&gt; Arrow -&gt; DuckDB Appender -&gt; validation -&gt; atomic activation. |

```
Application starts
        |
        v
financial V12 ACTIVE exists?
        |
   yes--+--no
   |        |
   v        v
 reuse    load from Dremio -> financial_v1_building.duckdb -> validate -> financial V1 ACTIVE
   |        |
   v        v
queries available immediately, no startup refresh either way
```

### `ALWAYS_REFRESH`

| Existing ACTIVE version? | Behavior |
|---|---|
| Yes | Made available to queries **immediately** (identical to `USE_EXISTING_OR_CREATE`'s reuse case) - and a new version additionally starts building in the background through the normal refresh pipeline. Once it validates, it cuts over atomically; the existing version is **never** deleted or made unavailable before its replacement is ready. If the background refresh fails, the existing version simply stays ACTIVE - see "Failure with an existing cache" below. |
| No | Identical to `USE_EXISTING_OR_CREATE`'s no-cache case (the mandatory rule) - there is nothing to "keep serving" while the first load runs. |

```
Application starts

financial V12 ACTIVE, financial V11 PREVIOUS
        |
        v
recover V12 -> query traffic can use V12 immediately
        |
        v
startup background refresh begins: Dremio -> Arrow -> financial_v13_building.duckdb
        |
        v
queries continue using V12 throughout - never blocked, never interrupted
        |
        v
V13 completes, validates
        |
        v
atomic switch: V13 ACTIVE, V12 PREVIOUS (old V11 cleaned up once safe, per the normal
two-version retention policy)
```

**This is never a destructive replace.** The implementation never deletes the current ACTIVE
version and then loads its replacement - it always builds the new version fully, validates it,
and only then atomically flips ACTIVE/PREVIOUS (`VersionManager.activate`, the same atomic
metadata transaction used by every other refresh path). "Always load and replace on startup" is a
correct *description* of `ALWAYS_REFRESH`, but internally "replace" always means safe version
switching, never `delete-then-reload`.

## `required` (per dataset)

`data-cache.datasets.<name>.required` (default `true`) marks whether a dataset counts toward
readiness under the blocking execution mode below. A dataset with `required: false` may remain
unavailable without holding back the whole application's readiness probe - useful for optional or
slow-to-load reference data that most requests don't need immediately.

## `StartupExecutionMode` (global)

```java
public enum StartupExecutionMode {
    ASYNC,
    BLOCK_UNTIL_REQUIRED_CACHE_READY
}
```

Configured globally: `data-cache.startup.execution-mode` (default `ASYNC`), with
`data-cache.startup.timeout` (default `30m`) bounding the blocking mode.

### `ASYNC` (default, recommended)

The application starts and reports ready normally - the startup coordinator only *decides and
triggers* work synchronously (which datasets need a load, and whether an existing one is
reused or a background refresh begins); it never waits for that work to finish. A dataset that is
still loading answers queries with a `DATASET_NOT_AVAILABLE` error (HTTP 503 from the REST API)
until its first load completes:

```
financial V10 exists, organization has no cache

Application starts
        |
        v
financial: READY immediately (V10 reused)
organization: STARTUP_LOADING (initial load in progress)
        |
        v
Queries requiring only financial: succeed immediately
Queries requiring organization: fail cleanly with DATASET_NOT_AVAILABLE (reason: still loading)
        |
        v
organization's load finishes -> organization V1 ACTIVE
        |
        v
Queries requiring organization now succeed
```

### `BLOCK_UNTIL_REQUIRED_CACHE_READY`

After triggering every dataset's startup work exactly as `ASYNC` does, the startup coordinator
additionally **blocks the startup sequence** (bounded by `data-cache.startup.timeout`) until every
dataset with `required: true` that needed an initial load has finished - success or failure. This
never hangs the application indefinitely: on timeout, the wait simply ends, loading continues in
the background exactly as in `ASYNC` mode, and the `dataCacheReadiness` Actuator health
indicator - which only actively gates readiness in this mode - reports `DOWN` for as long as a
required dataset remains unavailable. To wire it into an Actuator readiness probe:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,dataCacheReadiness
```

With that in place, a Kubernetes/OpenShift readiness probe hitting `/actuator/health/readiness`
will correctly report not-ready until required datasets are usable, and the deployment's rollout
will wait accordingly - without the application process itself ever being held up or crashed.

## The mandatory-auto-create rule, illustrated

First deployment - `/data/cache` starts completely empty:

```
Application starts
        |
        v
financial, organization, headcount all have no ACTIVE version
        |
        v
DataCacheStartupCoordinator triggers an initial load for each (bounded by
data-cache.refresh.max-concurrent-datasets), regardless of each dataset's configured startup.mode
        |
        v
/data/cache/financial/financial_v1.duckdb        financial     V1 ACTIVE
/data/cache/organization/organization_v1.duckdb  organization  V1 ACTIVE
/data/cache/headcount/headcount_v1.duckdb        headcount     V1 ACTIVE
```

No manual API call is required for this first load, on any dataset, under any `startup.mode`.

## Startup sequence and ordering

```
Spring Boot starts
        |
        v
data-cache.* configuration validation (DataCacheConfigurationValidator - fails fast on bad config)
        |
        v
Storage/metadata initialization (MetadataStore opens/creates cache_metadata.duckdb)
        |
        v
StartupRecoveryService.recoverAll() - reconciles metadata against physical files (see
07-REFRESH-AND-VERSIONING.md#startup-recovery): interrupted BUILDING/VALIDATING versions are
discarded, a missing ACTIVE file is marked FAILED, orphan files are removed
        |
        v
Query/status services can now correctly identify every dataset's recovered ACTIVE/PREVIOUS version
        |
        v
DataCacheStartupCoordinator.runStartupSequence(): evaluates startup.mode per enabled dataset,
triggers whatever initial/background loads are required, and (only in BLOCK_UNTIL_REQUIRED_CACHE_READY)
waits for required datasets
        |
        v
DynamicRefreshScheduler.start(): cron triggers are registered only now, after the coordinator has
already decided what startup-time work is needed
```

This ordering means a scheduled refresh can never fire before the startup coordinator has made its
decision for the same dataset. It is not, by itself, what prevents the two from running
*concurrently* though - that protection is `RefreshLock`, described next.

## Startup work always goes through the same refresh pipeline

`DataCacheStartupCoordinator` never loads data itself. Every load or refresh it triggers - the
mandatory initial load, or an `ALWAYS_REFRESH` background refresh - goes through
`DataCacheRefreshService.refreshAsync(datasetName)`, the exact same entry point used by
`DynamicRefreshScheduler` (cron) and the REST/Java admin API (manual refresh):

```
DataCacheStartupCoordinator  ---\
DynamicRefreshScheduler (cron)  ----> DataCacheRefreshService --> RefreshCoordinator (RefreshLock,
REST/Java admin API             ---/                              retry, validation, atomic activation)
```

Because they share one pipeline, they share one `RefreshLock` per dataset: **only one refresh of a
given dataset can ever run at a time, regardless of what triggered it.** If a startup-triggered
refresh for `financial` is still running when its `refresh-cron` fires (or an operator calls the
manual refresh endpoint), the second attempt immediately gets `ALREADY_RUNNING` rather than running
concurrently - proven directly by
`DataCacheStartupCoordinatorTest.startupTriggeredRefreshAndConcurrentSchedulerTriggeredRefreshNeverRunSimultaneously`.

## Failure behavior

### Failure with an existing cache (`ALWAYS_REFRESH`)

```
startup.mode = ALWAYS_REFRESH, financial V12 ACTIVE

Startup background refresh (V13) fails - network error, bad SQL, validation failure, anything
        |
        v
V12 remains ACTIVE - queries keep working throughout and afterward
Status: lastRefreshStatus = FAILED, but availability = AVAILABLE (there is a usable ACTIVE version)
```

The dataset is never reported unavailable just because a background refresh failed while a good
version was already serving traffic.

### Failure with no existing cache (mandatory first load)

```
No ACTIVE version anywhere for this dataset

First load fails (any reason)
        |
        v
Status: lastRefreshStatus = FAILED, activeVersion = null, availability = UNAVAILABLE
```

Queries against this dataset fail cleanly with `DatasetNotAvailableException`
(`DATASET_NOT_AVAILABLE`, HTTP 503 via the REST API) rather than throwing an unclear error or
hanging. The configured retry policy already ran (retryable failures are retried per
`retry.max-attempts`/backoff before this state is reached); a later scheduled or manual refresh can
still recover the dataset.

## Query-time availability

`DatasetStatus.availability()` (derived, never stored separately - see
`com.enterprise.datacache.model.DatasetAvailability`) distinguishes the three states a query-facing
caller needs to tell apart:

| `activeVersion` | `refreshInProgress` | `availability()` | Meaning |
|---|---|---|---|
| present | either | `AVAILABLE` | Queries succeed |
| `null` | `true` | `STARTUP_LOADING` | First-ever load in progress - try again shortly |
| `null` | `false` | `UNAVAILABLE` | No load ever succeeded, and none is running now |

(A dataset can only have `activeVersion == null` and `refreshInProgress == true` simultaneously
during its very first load: ACTIVE is only ever replaced atomically at the end of a successful
refresh, so once a dataset has an ACTIVE version, it always has one - old or new - for the rest of
its lifetime.)

`DuckDbQueryEngine` throws `DatasetNotAvailableException` with the precise reason whenever a query
references a dataset that isn't `AVAILABLE`; `data-cache-app`'s `GlobalExceptionHandler` maps it to
HTTP `503 SERVICE_UNAVAILABLE` with body `{"errorCode":"DATASET_NOT_AVAILABLE", "message": "..."}`.

## Retention still applies

Startup-triggered refreshes - whether the mandatory first load or an `ALWAYS_REFRESH` background
build - go through the exact same `VersionManager.activate()` used everywhere else, so the normal
two-version (ACTIVE + PREVIOUS) retention policy is unaffected:

```
Before startup:  financial V10 ACTIVE, V9 PREVIOUS
Startup (ALWAYS_REFRESH) builds V11 and activates it
After startup:   financial V11 ACTIVE, V10 PREVIOUS   (V9 cleaned up once safe)
```

## Cache lifecycle summary table

| Scenario | Existing ACTIVE cache? | `startup.mode` | Behavior |
|---|---|---|---|
| First deployment | No | any | Auto-create: initial load triggered automatically, no manual call needed |
| Restart | Yes | `USE_EXISTING_OR_CREATE` | Reuse existing ACTIVE immediately; no startup refresh |
| Restart | Yes | `ALWAYS_REFRESH` | Reuse existing ACTIVE immediately; new version also builds in the background |
| Startup refresh fails | Yes | `ALWAYS_REFRESH` | Existing ACTIVE version is kept; failure recorded; dataset stays `AVAILABLE` |
| First load fails | No | any | Dataset stays `UNAVAILABLE`; queries fail cleanly with `DATASET_NOT_AVAILABLE`; retry policy already applied |
| Scheduled refresh succeeds | Yes | N/A (unrelated to startup) | New ACTIVE version, old one becomes PREVIOUS |
| Scheduled refresh fails | Yes | N/A (unrelated to startup) | Current ACTIVE version is kept |

## Tests that prove this document

| Scenario | Test |
|---|---|
| Empty cache auto-creates first version, no manual trigger | `DataCacheStartupCoordinatorTest.emptyCacheAutomaticallyCreatesFirstVersionOnStartupWithNoManualTrigger` |
| Existing cache reused, no startup refresh | `DataCacheStartupCoordinatorTest.existingActiveCacheIsReusedWithoutStartupRefreshInUseExistingOrCreateMode` |
| `ALWAYS_REFRESH` keeps old version serving while building the new one | `DataCacheStartupCoordinatorTest.alwaysRefreshKeepsExistingActiveImmediatelyAvailableWhileBuildingNewVersionInBackground` |
| `ALWAYS_REFRESH` startup failure keeps the existing cache | `DataCacheStartupCoordinatorTest.alwaysRefreshStartupFailureLeavesExistingActiveCacheIntact` |
| First-load failure leaves the dataset cleanly unavailable | `DataCacheStartupCoordinatorTest.firstStartupLoadFailureLeavesDatasetCleanlyUnavailable` |
| Restart across two independent process instances | `DataCacheStartupCoordinatorTest.restartAfterSuccessfulCacheReusesActiveVersionWithoutReloading` |
| Startup refresh vs. concurrent scheduler-triggered refresh | `DataCacheStartupCoordinatorTest.startupTriggeredRefreshAndConcurrentSchedulerTriggeredRefreshNeverRunSimultaneously` |
| Blocking execution mode waits for a required dataset | `DataCacheStartupCoordinatorTest.blockingExecutionModeWaitsForRequiredDatasetBeforeReturning` |
| Blocking execution mode times out without hanging | `DataCacheStartupCoordinatorTest.blockingExecutionModeGivesUpAfterTimeoutWithoutHangingAndKeepsLoadingInBackground` |
| Blocking execution mode ignores non-required datasets | `DataCacheStartupCoordinatorTest.blockingExecutionModeDoesNotWaitForNonRequiredDataset` |
| Readiness indicator behavior in both execution modes | `DataCacheReadinessIndicatorTest` (4 cases) |
| Full end-to-end through real Spring Boot auto-configuration | `EmbeddingAcceptanceTest.springBootAutoConfiguresCoreServicesAndFullStartupThenRefreshQueryLifecycleWorks` |
| Mandatory auto-create attempted at the REST/app level | `DataCacheApplicationTests.mandatoryStartupAutoCreateAttemptedAndFailedCleanlyWithoutLiveDremio` |
| Query against a dataset with no cache fails as 503 | `DataCacheApplicationTests.queryAgainstDatasetWithNoActiveCacheFailsCleanlyAsServiceUnavailable` |

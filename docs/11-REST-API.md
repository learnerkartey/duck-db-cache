# 11. REST API

All endpoints are implemented in `feature.cache.api` (`AdminController`, `QueryController`) -
optional and enabled by default; set `data-cache.api.enabled=false` to disable them. Controllers
only call the public services (`DataCacheQueryService`, `DataCacheRefreshService`,
`DataCacheStatusService`) - no query/refresh/version logic is duplicated here.

## `POST /api/v1/cache/query/{queryName}`

Executes a registered query. **There is no endpoint that accepts arbitrary SQL.**

Request body:

```json
{
  "parameters": { "fiscalYear": 2026 },
  "page": 0,
  "size": 100
}
```

`parameters`, `page`, and `size` are all optional; missing `page`/`size` fall back to the
configured defaults. Response body is a `PagedQueryResult` (see
[10-USING-QUERY-SERVICE.md](10-USING-QUERY-SERVICE.md#pagedqueryresult)).

Errors: `404` with `{"errorCode":"QUERY_NOT_FOUND", ...}` for an unregistered name; `400` with
`{"errorCode":"MISSING_QUERY_PARAMETER", ...}` for an unbound `:name`.

## `POST /api/v1/cache/admin/datasets/{datasetName}/refresh`

Triggers a manual refresh and blocks until it completes. Response body is a
`DatasetRefreshResult`:

```json
{
  "datasetName": "financial",
  "outcome": "SUCCESS",
  "newVersion": 11,
  "activeVersion": 11,
  "timings": { "dremioSetupMs": 120, "timeToFirstBatchMs": 340, "arrowTransferMs": 8200,
               "duckDbWriteMs": 41000, "validationMs": 300, "activationMs": 5,
               "totalMs": 49965, "rowsLoaded": 31284901, "bytesLoaded": 2147483648,
               "batchesLoaded": 486 },
  "errorCode": null,
  "errorMessage": null,
  "attemptsMade": 1
}
```

`outcome` is one of `SUCCESS`, `FAILED`, `VALIDATION_FAILED`, `ALREADY_RUNNING`, `DISABLED`,
`PAUSED_RETRYABLE` (a resumable dataset's BUILDING version paused after a failure - ACTIVE is
unaffected), `NO_RESUMABLE_BUILD` (only returned by `/resume` below). For a resumable dataset with
an existing compatible BUILDING version, this endpoint already resumes it rather than starting a
new version - see [RESUMABLE-REFRESH-AND-RECOVERY.md](RESUMABLE-REFRESH-AND-RECOVERY.md).

## `POST /api/v1/cache/admin/datasets/{datasetName}/resume`

Same response shape as `/refresh` above, but first confirms a resumable BUILDING manifest actually
exists for this dataset; if not, returns immediately with `outcome: NO_RESUMABLE_BUILD` rather than
starting a brand-new full load. Automatic startup resume never depends on this endpoint being
called - it exists for operator visibility/control.

## `POST /api/v1/cache/admin/datasets/{datasetName}/restart-refresh`

**Protected admin operation.** Intentionally abandons any partial BUILDING version for this dataset
- even one that would otherwise be safely resumable - and starts a brand-new version from zero. The
current ACTIVE version is never touched. Same response shape as `/refresh`.

## `POST /api/v1/cache/admin/refresh-all`

Refreshes every enabled dataset, respecting `data-cache.refresh.max-concurrent-datasets`, and
waits for all to finish. Response is `Map<String, DatasetRefreshResult>` keyed by dataset name.

## `GET /api/v1/cache/admin/datasets`

Response is `Map<String, DatasetStatus>` for every configured dataset:

```json
{
  "financial": {
    "datasetName": "financial",
    "enabled": true,
    "activeVersion": 11,
    "previousVersion": 10,
    "activeRowCount": 31284901,
    "lastSuccessfulRefresh": "2026-08-13T04:00:12Z",
    "lastRefreshStatus": "SUCCESS",
    "lastRefreshDurationMs": 49965,
    "lastError": null,
    "refreshInProgress": false,
    "buildingVersion": null,
    "refreshStage": null,
    "rowsProcessed": null,
    "batchesProcessed": null,
    "elapsedMs": null,
    "averageRowsPerSecond": null,
    "estimatedPercent": null,
    "resumable": false,
    "resuming": null,
    "completedChunks": null,
    "totalChunks": null,
    "rowsCommitted": null,
    "currentChunk": null,
    "failedChunk": null,
    "sourceSnapshotId": null,
    "pausedRetryable": false
  }
}
```

No passwords, raw SQL, or file paths are ever included. While a refresh is in flight,
`activeVersion` keeps reporting the version still serving queries and the fields from
`buildingVersion` onward describe the separate in-flight `buildingVersion` - see
[DATA-CACHE-DEVELOPER-GUIDE.md#22-status-and-health](DATA-CACHE-DEVELOPER-GUIDE.md#22-status-and-health)
for a worked mid-refresh example. The `resumable`/`resuming`/`completedChunks`/`totalChunks`/
`rowsCommitted`/`currentChunk`/`failedChunk`/`sourceSnapshotId`/`pausedRetryable` fields are only
meaningful for datasets with `resume.enabled: true` - see
[RESUMABLE-REFRESH-AND-RECOVERY.md §16](RESUMABLE-REFRESH-AND-RECOVERY.md#16-operational-status).

## `GET /api/v1/cache/admin/datasets/{datasetName}`

Single-dataset form of the above. `404` with `errorCode: DATASET_NOT_FOUND` for an unknown name.

## Actuator endpoints

`/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` (see
`application.yml`). Health separates the `dataCache` component from the `dremioSource` component -
see [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md).

## Error body shape

Every error response is:

```json
{ "errorCode": "DATASET_NOT_FOUND", "message": "No enabled dataset configured with name 'foo'" }
```

`GlobalExceptionHandler` maps `DatasetNotFoundException`/`QueryNotFoundException` to `404`,
`MISSING_QUERY_PARAMETER`/`INVALID_CONFIGURATION` to `400`, everything else `DataCacheException`-derived
to `500`, and any other unexpected exception to a generic `500 INTERNAL_ERROR` body with no stack
trace.

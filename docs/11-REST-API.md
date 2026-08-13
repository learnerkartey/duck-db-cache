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
               "totalMs": 49965, "rowsLoaded": 31284901, "bytesLoaded": 2147483648 },
  "errorCode": null,
  "errorMessage": null,
  "attemptsMade": 1
}
```

`outcome` is one of `SUCCESS`, `FAILED`, `VALIDATION_FAILED`, `ALREADY_RUNNING`, `DISABLED`.

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
    "refreshInProgress": false
  }
}
```

No passwords, raw SQL, or file paths are ever included.

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

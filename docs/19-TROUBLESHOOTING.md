# 19. Troubleshooting

| Problem | Diagnosis | Fix |
|---|---|---|
| **Dremio auth errors** (`UNAUTHENTICATED`/`UNAUTHORIZED`) | `errorCode: DREMIO_SOURCE_ERROR`, non-retryable, `attemptsMade: 1` | Verify `DREMIO_USERNAME`/`DREMIO_PASSWORD`; these are never retried since retrying bad credentials cannot succeed |
| **Flight SQL connection failures** | `errorCode: DREMIO_SOURCE_ERROR`, `retryable=true`, multiple attempts logged | Check `DREMIO_HOST`/`DREMIO_PORT`/network path/`ssl-enabled`; see [04-DREMIO-CONFIGURATION.md](04-DREMIO-CONFIGURATION.md) |
| **Arrow memory issues / OOM in refresh** | JVM or container OOM during a refresh | Increase `data-cache.arrow.max-memory`; check whether Dremio is returning unusually wide/large batches |
| **DuckDB file permission errors** | `DUCKDB_WRITE_ERROR` on `PRAGMA`/`CREATE TABLE` | Verify the process UID can write `duckdb.base-directory` and `duckdb.temp-directory` (see the Dockerfile's non-root user) |
| **PVC / disk full** | Refresh fails while writing; `df` on the mounted volume shows 100% | Lower `duckdb.max-versions`; check for orphaned `_building` files from crashes (startup recovery cleans these on next restart) |
| **Dataset unavailable** | `DatasetNotFoundException` from the query engine | The dataset has never successfully refreshed - trigger one manually |
| **Missing column in query** | `MissingQueryParameterException` or a DuckDB "column not found" error | If the column isn't in the cache yet, update `source-sql` and refresh first - see [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md#when-source-sql-must-change) |
| **Validation failure** | `outcome: VALIDATION_FAILED`, `errorCode: VALIDATION_FAILED` | Check `errorMessage` for which rule failed (row count / column / custom SQL); the previous ACTIVE version is untouched |
| **Slow refresh** | High `totalMs` | Use the phase timing breakdown - see [15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md#bottleneck-diagnosis) |
| **Slow DuckDB query** | High `executionTimeMs` in `PagedQueryResult` | Check join cardinality and whether the query filters/aggregates before returning rows |
| **Many-to-many join explosion** | Row counts far higher than expected in a multi-dataset query | Verify each joined table's actual grain - see [08-WRITING-DUCKDB-QUERIES.md](08-WRITING-DUCKDB-QUERIES.md#three-dataset-join) |
| **Pod OOM** | Container killed (`OOMKilled`) | Re-check total memory budget - see [16-MEMORY-SIZING.md](16-MEMORY-SIZING.md); DuckDB memory multiplies with concurrent query connections |
| **Startup recovery issues** | Dataset stuck reporting `activeVersion: null` after a crash/restart | Check logs for `event=startup-recovery-active-file-missing`; the physical file was lost - refresh again |
| **Duplicate refresh rejected** | `outcome: ALREADY_RUNNING` | Expected behavior - only one refresh per dataset runs at a time; wait or check `refreshInProgress` via the status endpoint |

## Reading structured logs

Every refresh attempt logs exactly one completion line (`event=dataset-refresh-completed`) with
`dataset`, `version` (on success), `rows`, `durationMs`, `rowsPerSecond`, and `status`. Failed
attempts additionally log `event=refresh-attempt-failed` per retry with `retryable` and whether it
was the terminal attempt.

# 02. Architecture

## Data flow

```
                         ┌───────────────────────────────────────────────┐
                         │                  REFRESH PATH                  │
                         └───────────────────────────────────────────────┘
Dremio (Arrow Flight SQL)
   │  DremioFlightSqlSource: FlightClient -> authenticateBasicToken -> FlightSqlClient.execute(sql)
   │  -> FlightInfo -> iterate FlightEndpoints -> getStream(ticket) -> FlightStream
   ▼
ArrowBatchStream (bounded memory - one VectorSchemaRoot batch in flight at a time)
   │
   ▼
DuckDbDatasetWriter (ArrowToDuckDbTypeMapper + DuckDB Appender, one row-copy per Arrow value,
   │                 no per-row SQL, periodic flush)
   ▼
<dataset>_v<N>_building.duckdb  --[validation passes]-->  <dataset>_v<N>.duckdb
   │
   ▼
MetadataStore.activateVersion (atomic: N -> ACTIVE, old ACTIVE -> PREVIOUS)
   │
   ▼
VersionManager (reader-refcounted retention/cleanup of versions beyond data-cache.duckdb.max-versions)


                         ┌───────────────────────────────────────────────┐
                         │                   QUERY PATH                   │
                         └───────────────────────────────────────────────┘
DataCacheQueryService.execute(queryName, params, page, size)
   │
   ▼
QueryRegistry (queryName -> SQL text + NamedParameterSqlBinder.BoundSql + dataset list)
   │
   ▼
VersionManager.pinActive(dataset) for every dataset the query references  -> List<VersionHandle>
   │
   ▼
DuckDbQueryEngine: open one isolated in-process DuckDB connection
   -> ATTACH each pinned version's file read-only under a private alias
   -> CREATE VIEW <logicalDatasetName> AS SELECT * FROM <alias>.<table>
   -> execute the registered SQL (COUNT(*) OVER() + LIMIT/OFFSET pushed into DuckDB)
   │
   ▼
PagedQueryResult   (VersionHandles released in a finally block either way)
```

## Package layout (`com.enterprise.datacache.feature.cache`)

This is a single, self-contained Java package - the entire cache implementation - designed to be
copied wholesale into any Spring Boot 3 application. Nothing outside it (in particular,
`DataCacheApplication`, the standalone demo app's entry point) is part of the feature; nothing
inside it depends on anything outside it. See
[INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md](INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md).

| Package | Responsibility |
|---|---|
| `config` | `DataCacheProperties` and nested `@ConfigurationProperties`, `DataCacheConfiguration`, config validation |
| `spi` | `DremioSource` / `ArrowBatchStream` - the pluggable boundary to the external source |
| `dremio` | Real Arrow Flight SQL client implementation of `DremioSource` |
| `arrow` | Arrow -> DuckDB type mapping |
| `duckdb` | File-backed DuckDB connection factory and the Appender-based bulk writer |
| `metadata` | `MetadataStore` - persistent version metadata in its own DuckDB file |
| `version` | `VersionManager` - ACTIVE/PREVIOUS lifecycle, reader reference counting, safe deletion |
| `refresh` | `RefreshCoordinator`, retry, per-dataset locking, dynamic cron scheduler, startup recovery, startup lifecycle coordinator, `DataCacheRefreshService` |
| `query` | `QueryRegistry`, `DuckDbQueryEngine`, named-parameter binder, `DataCacheQueryService` |
| `validation` | Row-count / required-column / custom-SQL validation |
| `metrics` | `DataCacheMetrics` (Micrometer) |
| `health` | `DataCacheStatusService`, Actuator health indicators |
| `model` | Public value types (`DatasetVersion`, `DatasetStatus`, `PagedQueryResult`, ...) |
| `exception` | Typed exception hierarchy with retryable/non-retryable classification |
| `benchmark` | Standalone DuckDB writer throughput benchmark |
| `util` | `SqlResourceLoader`, `Sha256` |
| `api` | Optional REST controllers (`AdminController`, `QueryController`) and DTOs - the only subpackage that depends on Spring Web/MVC |

`DataCacheApplication` (outside `feature.cache`, in `com.enterprise.datacache`) is only a
standalone demo entry point - `@SpringBootApplication` plus `@EnableDataCache` - so the feature can
run and be tested on its own. A host application copies `feature.cache` and adds its own
`@EnableDataCache`-annotated class; it never needs `DataCacheApplication` itself.

## Public services (the embedding surface)

```java
DataCacheQueryService.execute(String queryName, Map<String,Object> params, int page, int size)
DataCacheRefreshService.refresh(String datasetName)          // and refreshAsync / refreshAll
DataCacheStatusService.getStatus(String datasetName)         // and getAllStatuses
```

All three are ordinary Spring beans registered by `DataCacheConfiguration` (imported by
`@EnableDataCache`) and can be `@Autowired` into any other bean. See
[INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md](INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md).

## Concurrency model

- **Per-dataset refresh lock** (`RefreshLock`): at most one refresh per dataset at a time.
- **Bounded refresh executor** (`DataCacheRefreshServiceImpl`): a fixed thread pool sized by
  `data-cache.refresh.max-concurrent-datasets` - every refresh trigger (manual, scheduled,
  `refresh-all`) goes through it.
- **Query version pinning** (`VersionManager.pinActive`): each query call increments an in-memory
  reader count for the dataset's current ACTIVE version before it does anything else, so a
  refresh that activates a newer version concurrently never invalidates an in-flight query.
- **Metadata store**: one long-lived DuckDB connection, all access `synchronized` on the
  `MetadataStore` instance (metadata operations are infrequent compared to query traffic).
- **Query execution**: each query opens its own short-lived, isolated DuckDB connection so that
  two concurrent queries pinning *different* versions of the same dataset never collide on
  `ATTACH` aliases.

## Single-writer-replica assumption

`RefreshLock`, `VersionManager`, and the metadata store coordinate refreshes **within one JVM
process**. Running multiple replicas that each independently refresh the same shared storage is
not safe without an external distributed lock - see [18-OPERATIONS.md](18-OPERATIONS.md).

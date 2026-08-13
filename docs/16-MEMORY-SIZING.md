# 16. Memory Sizing

**Do not assume `-Xmx` is total pod memory usage.** Total pod memory must account for:

| Component | Controlled by | Notes |
|---|---|---|
| JVM heap | `-Xmx` | Java objects: config, metadata rows, query result pages (bounded by page size) |
| Arrow off-heap (native) | `data-cache.arrow.max-memory` | One shared root allocator; Arrow buffers for in-flight batches |
| DuckDB native memory | `data-cache.duckdb.memory-limit` | **Per open DuckDB connection** - the refresh writer connection plus every concurrently-open query connection each count separately |
| Netty/native buffers | JVM default + `-Dio.netty.*` | Used by Arrow Flight's gRPC transport |
| Thread stacks | `-Xss` × thread count | Refresh executor, scheduler pool, web server threads |
| OS/container overhead | - | Page cache, glibc/malloc overhead, JIT code cache |

## Why DuckDB memory multiplies

Every query execution opens its own isolated DuckDB connection (deliberately, to avoid `ATTACH`
alias collisions between concurrent queries pinning different versions - see
[02-ARCHITECTURE.md](02-ARCHITECTURE.md#concurrency-model)). Each such connection independently
applies `PRAGMA memory_limit`. Under `N` concurrent queries plus one active refresh, worst-case
DuckDB memory usage approaches `(N + 1) × data-cache.duckdb.memory-limit`. Size
`data-cache.duckdb.memory-limit` with your expected peak concurrent query count in mind, not just
the largest single dataset.

## Sizing guidance

1. Start with `arrow.max-memory` at least 2-3x your largest expected single Arrow batch size
   (batch size is controlled by Dremio/Flight, not this service - if you see OOM here, it usually
   means Dremio is returning unusually large batches for a wide/high-cardinality query).
2. Set `duckdb.memory-limit` per-connection, then multiply by realistic peak concurrency
   (`refresh.max-concurrent-datasets` + expected concurrent query load) to get the DuckDB
   contribution to pod memory.
3. Set the container memory **limit** to at least
   `heap + arrow.max-memory + (peak-concurrent-duckdb-connections × duckdb.memory-limit) + 512MB overhead`.
4. Set the container memory **request** conservatively below the limit - DuckDB and Arrow only use
   memory proportional to actual load, not the configured ceiling, so requests can be lower than
   limits for a typically-idle service.

See [20-OPENSHIFT-DEPLOYMENT.md](20-OPENSHIFT-DEPLOYMENT.md) for example resource blocks.

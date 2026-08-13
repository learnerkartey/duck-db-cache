# data-cache-service

A production-grade Java 17 / Spring Boot service that streams data from **Dremio** (via Apache
Arrow Flight SQL) into **versioned, file-backed DuckDB caches**, and serves fast analytical queries
- including cross-dataset joins - directly out of DuckDB with zero-downtime refresh.

```
Dremio --(Arrow Flight SQL)--> Arrow batches --(bounded memory)--> DuckDB Appender
   --> versioned .duckdb files --> ACTIVE/PREVIOUS lifecycle --> DuckDB query engine --> REST/Java API
```

## Modules

```
data-cache-service/
├── data-cache-core/   Reusable library: Dremio client, Arrow->DuckDB writer, metadata,
│                      versioning, refresh orchestration, query engine, Spring auto-configuration.
│                      No REST, no web dependency. Embeddable in any Spring Boot 3 app.
└── data-cache-app/    Standalone Spring Boot REST application - thin controllers over
                       data-cache-core's public services. Nothing else lives here.
```

`data-cache-app` depends on `data-cache-core`. The reverse is enforced by an ArchUnit test
(`ArchitectureTest`) and is structurally impossible in the Gradle module graph.

## Quick start

```bash
./gradlew clean build
./gradlew :data-cache-app:bootRun
```

The app starts without a live Dremio connection (Dremio is only contacted when a dataset is
actually refreshed). See [docs/01-QUICK-START.md](docs/01-QUICK-START.md).

## Documentation

New to this project? Start with the
**[Data Cache Developer Guide](docs/DATA-CACHE-DEVELOPER-GUIDE.md)** - a single, complete,
start-to-finish walkthrough (what the service does, storage layout, first start, restarts,
Dremio config, adding datasets/queries, joins, pagination, refresh, failure behavior, monitoring,
troubleshooting, and embedding). The specialized documents below go deeper on each topic.

| # | Document | Covers |
|---|----------|--------|
| 01 | [Quick Start](docs/01-QUICK-START.md) | Build, run, first refresh, first query |
| 02 | [Architecture](docs/02-ARCHITECTURE.md) | Component diagram, data flow, package layout |
| 03 | [Configuration Reference](docs/03-CONFIGURATION-REFERENCE.md) | Every `data-cache.*` property |
| 04 | [Dremio Configuration](docs/04-DREMIO-CONFIGURATION.md) | Flight SQL connection, auth, live verification |
| 05 | [Dataset Configuration](docs/05-DATASET-CONFIGURATION.md) | Adding/tuning datasets |
| 06 | [DuckDB Cache](docs/06-DUCKDB-CACHE.md) | File layout, storage, Appender ingestion |
| 07 | [Refresh & Versioning](docs/07-REFRESH-AND-VERSIONING.md) | Lifecycle, locking, retry, cleanup |
| 08 | [Writing DuckDB Queries](docs/08-WRITING-DUCKDB-QUERIES.md) | JOIN/GROUP BY/CTE/window function examples |
| 09 | [Query Configuration](docs/09-QUERY-CONFIGURATION.md) | Registering analytical queries |
| 10 | [Using the Query Service](docs/10-USING-QUERY-SERVICE.md) | Java API usage |
| 11 | [REST API](docs/11-REST-API.md) | Endpoint reference |
| 12 | [Adding a New Dataset](docs/12-ADDING-A-NEW-DATASET.md) | Step-by-step, no Java required |
| 13 | [Adding a New Query](docs/13-ADDING-A-NEW-QUERY.md) | Step-by-step, no Java required |
| 14 | [Validation](docs/14-VALIDATION.md) | Row count / columns / custom SQL checks |
| 15 | [Performance Tuning](docs/15-PERFORMANCE-TUNING.md) | Benchmarking, bottleneck diagnosis |
| 16 | [Memory Sizing](docs/16-MEMORY-SIZING.md) | JVM heap vs. Arrow vs. DuckDB memory |
| 17 | [Metrics & Monitoring](docs/17-METRICS-AND-MONITORING.md) | Micrometer metrics reference |
| 18 | [Operations](docs/18-OPERATIONS.md) | Runbook, multi-replica caveats |
| 19 | [Troubleshooting](docs/19-TROUBLESHOOTING.md) | Common failure modes |
| 20 | [OpenShift Deployment](docs/20-OPENSHIFT-DEPLOYMENT.md) | Manifests, PVC, resources, probes |
| 21 | [Embedding in an Existing Service](docs/21-EMBEDDING-IN-EXISTING-SERVICE.md) | Using `data-cache-core` as a library |
| 22 | [End-to-End Example](docs/22-EXAMPLES-END-TO-END.md) | Full worked scenario, including a restart |
| 23 | [Startup Cache Lifecycle](docs/23-STARTUP-CACHE-LIFECYCLE.md) | First-start auto-create, restart behavior, `StartupMode`, blocking vs. async startup |

## Technology

Java 17 · Spring Boot 3.3 · Apache Arrow / Arrow Flight SQL 17.0.0 · DuckDB JDBC 1.3.2.1 ·
Micrometer · Spring Boot Actuator · JUnit 5 · Mockito · ArchUnit · Gradle (multi-module)

## Status

All 61 automated tests (unit, concurrency, startup-lifecycle, and Spring-context integration tests
across both modules) pass under `./gradlew clean build`. See [docs/15-PERFORMANCE-TUNING.md](docs/15-PERFORMANCE-TUNING.md)
for real, locally-measured DuckDB writer throughput numbers, and
[docs/04-DREMIO-CONFIGURATION.md](docs/04-DREMIO-CONFIGURATION.md) for how to verify live Dremio
connectivity (not required for the normal build).

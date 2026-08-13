# 01. Quick Start

## Prerequisites

- Java 17+ (JDK 21 also works - the build targets `--release 17`)
- Network access to Maven Central (or a configured mirror) for the first build

## Build

```bash
./gradlew clean build
```

This compiles the project, runs the full test suite (unit tests, DuckDB integration tests using
real temporary `.duckdb` files, concurrency tests, ArchUnit portability tests, and Spring Boot
context tests including a host-application embedding test), and produces
`build/libs/data-cache-service.jar`.

No live Dremio connection is required for this build - Dremio connects lazily on first use, and
the unit/integration tests replace it with an in-memory test double (`InMemoryDremioSource`,
test-scope only).

## Run the standalone REST app

```bash
export DATA_CACHE_BASE_DIR=/tmp/data-cache
./gradlew bootRun
```

With the default `application.yml`, the app starts with three datasets configured
(`financial`, `organization`, `headcount`) and three queries registered
(`financial-summary`, `cfo-summary`, `headcount-summary`). Because none of them has a cache yet,
the mandatory startup auto-create rule (see
[23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md)) immediately tries to load all
three from Dremio in the background - the app itself still comes up right away
(`data-cache.startup.execution-mode` defaults to `ASYNC`), and without real Dremio credentials
those background loads simply fail cleanly and get recorded as `FAILED`, which you can see below.

Check status:

```bash
curl localhost:8080/api/v1/cache/admin/datasets
```

Without real Dremio credentials configured, every dataset will report
`"lastRefreshStatus":"FAILED"` with `"errorCode":"DREMIO_SOURCE_ERROR"` - that is the mandatory
auto-create attempt that already ran on its own, not something you need to trigger.

## Point at a real Dremio and load data

```bash
export DREMIO_HOST=dremio.example.internal
export DREMIO_PORT=32010
export DREMIO_USERNAME=svc_datacache
export DREMIO_PASSWORD=********
export DATA_CACHE_BASE_DIR=/data/cache
```

Replace the example source SQL in `src/main/resources/datacache/dremio/*.sql` with
your real Dremio table paths (see [05-DATASET-CONFIGURATION.md](05-DATASET-CONFIGURATION.md)),
then:

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/financial/refresh
```

This runs synchronously and returns a `DatasetRefreshResult` with a full timing breakdown
(Dremio setup, time-to-first-batch, Arrow transfer, DuckDB write, validation, activation).

## Run a query

```bash
curl -X POST localhost:8080/api/v1/cache/query/financial-summary \
  -H 'Content-Type: application/json' \
  -d '{"parameters": {"fiscalYear": 2026}, "page": 0, "size": 100}'
```

## Run the benchmark

```bash
./gradlew runBenchmark --args="--rows=5000000 --batch-size=100000"
```

See [15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md) for real, locally-measured numbers.

## Next

- [02-ARCHITECTURE.md](02-ARCHITECTURE.md) for how the pieces fit together
- [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md) to add your own dataset
- [INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md](INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md)
  to copy `feature.cache` into an existing Spring Boot application instead of running the standalone app

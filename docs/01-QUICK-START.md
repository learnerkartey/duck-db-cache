# 01. Quick Start

## Prerequisites

- Java 17+ (JDK 21 also works - the build targets `--release 17`)
- Network access to Maven Central (or a configured mirror) for the first build

## Build

```bash
./gradlew clean build
```

This compiles both modules, runs the full test suite (34 tests: unit tests, DuckDB integration
tests using real temporary `.duckdb` files, concurrency tests, and a Spring Boot context test),
and produces `data-cache-app/build/libs/data-cache-app.jar`.

No live Dremio connection is required for this build - Dremio connects lazily on first use, and
the unit/integration tests replace it with an in-memory test double (`InMemoryDremioSource`,
test-scope only).

## Run the standalone REST app

```bash
export DATA_CACHE_BASE_DIR=/tmp/data-cache
./gradlew :data-cache-app:bootRun
```

With the default `application.yml`, the app starts with three datasets configured
(`financial`, `organization`, `headcount`) and three queries registered
(`financial-summary`, `cfo-summary`, `headcount-summary`), all `load-on-startup: false` so the app
comes up immediately even without Dremio credentials.

Check status:

```bash
curl localhost:8080/api/v1/cache/admin/datasets
```

Every dataset will report `"lastRefreshStatus":"NEVER_RUN"` until refreshed.

## Point at a real Dremio and load data

```bash
export DREMIO_HOST=dremio.example.internal
export DREMIO_PORT=32010
export DREMIO_USERNAME=svc_datacache
export DREMIO_PASSWORD=********
export DATA_CACHE_BASE_DIR=/data/cache
```

Replace the example source SQL in `data-cache-app/src/main/resources/datacache/dremio/*.sql` with
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
./gradlew :data-cache-core:runBenchmark --args="--rows=5000000 --batch-size=100000"
```

See [15-PERFORMANCE-TUNING.md](15-PERFORMANCE-TUNING.md) for real, locally-measured numbers.

## Next

- [02-ARCHITECTURE.md](02-ARCHITECTURE.md) for how the pieces fit together
- [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md) to add your own dataset
- [21-EMBEDDING-IN-EXISTING-SERVICE.md](21-EMBEDDING-IN-EXISTING-SERVICE.md) to use
  `data-cache-core` inside an existing Spring Boot application instead of the standalone app

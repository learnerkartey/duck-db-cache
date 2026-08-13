# Integrating the Data Cache into an Existing Spring Boot Service

This guide walks through copying the `feature.cache` package - the entire Dremio → Arrow Flight
SQL → DuckDB cache implementation - into an **existing** Spring Boot 3 application. It assumes a
target service structured like this:

```
existing-finance-service/
├── build.gradle
├── src/main/java/com/company/finance/
│   ├── FinanceServiceApplication.java
│   ├── controller/...
│   ├── service/...
│   └── repository/...
└── src/main/resources/application.yml
```

Everything below is exact: real dependency coordinates from this repository's `build.gradle` and
`gradle.properties`, real property names from `DataCacheProperties` and
`src/main/resources/application.yml`, and real interface signatures. Nothing here is invented.

## What you're copying

Two things, and only two things:

1. **`src/main/java/com/enterprise/datacache/feature/cache/`** - every Java class the cache
   feature needs. This is a complete, self-contained package: config, SPI, Dremio client, Arrow
   handling, DuckDB writer/query engine, metadata, versioning, refresh orchestration, startup
   lifecycle, validation, metrics, health indicators, and (optionally) REST controllers.
2. **`src/main/resources/datacache/`** - Dremio source SQL, DuckDB analytical query SQL, and
   custom validation SQL. This is *your* business content (which tables you cache, which queries
   you run), deliberately kept separate from the Java code above.

Both are plain folders with no build tooling of their own - no separate `build.gradle`, no JAR, no
`settings.gradle` entry. You copy them the same way you'd copy any other package into your
codebase.

### 1. Copy the Java package

```bash
cp -r data-cache-service/src/main/java/com/enterprise/datacache/feature \
      existing-finance-service/src/main/java/com/enterprise/datacache/feature
```

**No package rename is required.** `feature.cache` does not need to live under
`com.company.finance` - it can sit in its own `com.enterprise.datacache.feature.cache` namespace
inside your application, side by side with your own `com.company.finance` packages, with zero
naming collisions. (If you prefer it under your own base package for organizational reasons, a
plain find/replace of the package declarations works too - nothing in the feature hard-codes its
own package name - but it is not necessary.)

### 2. Copy the SQL resources

```bash
cp -r data-cache-service/src/main/resources/datacache \
      existing-finance-service/src/main/resources/datacache
```

This gives you `src/main/resources/datacache/dremio/*.sql` (source extraction queries),
`src/main/resources/datacache/query/*.sql` (DuckDB analytical queries), and
`src/main/resources/datacache/validation/*.sql` (custom validation checks) as a starting point.
Replace the example SQL with your own datasets and queries (see the worked examples near the end
of this guide) - keep the folder structure.

## 3. Gradle dependencies

Add these to `existing-finance-service/build.gradle`. Versions are exactly what this repository
pins in `gradle.properties`.

**Likely already present in a typical Spring Boot service** - add only if missing:

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework:spring-tx'
}
```

`spring-boot-starter-web` is required if you want the optional REST controllers (see
[§8](#8-rest-controllers-optional)); `spring-boot-starter-actuator` is required for the two health
indicators (`DataCacheHealthIndicator`, `DremioSourceHealthIndicator`) and the readiness indicator
to register - without it those three classes simply won't compile, so keep it if you keep those
files. `spring-tx` and `spring-boot-starter-validation` are transitive of most Spring Boot web
services already.

**Cache-feature-specific - add these regardless:**

```gradle
ext {
    arrowVersion = '17.0.0'
    duckdbVersion = '1.3.2.1'
    micrometerVersion = '1.13.4'
}

dependencies {
    implementation "org.apache.arrow:flight-sql:${arrowVersion}"
    implementation "org.apache.arrow:flight-core:${arrowVersion}"
    implementation "org.apache.arrow:arrow-vector:${arrowVersion}"
    implementation "org.apache.arrow:arrow-memory-netty:${arrowVersion}"
    runtimeOnly "org.apache.arrow:arrow-memory-core:${arrowVersion}"

    implementation "org.duckdb:duckdb_jdbc:${duckdbVersion}"

    implementation "io.micrometer:micrometer-core:${micrometerVersion}"

    compileOnly "org.springframework.boot:spring-boot-configuration-processor"
    annotationProcessor "org.springframework.boot:spring-boot-configuration-processor"
}
```

If your Spring Boot version differs from `3.3.4`, that's fine - the feature has no hard dependency
on that exact patch version, only on Spring Boot 3.x APIs (`@ConfigurationProperties`,
`ConditionalOn*`, Actuator's `HealthIndicator`).

## 4. JVM arguments

Apache Arrow's Netty-based off-heap allocator needs reflective access to `java.nio` on JDK 17+.
Add these JVM args wherever your service's JVM args are configured:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
-Dio.netty.tryReflectionSetAccessible=true
```

**Locally** (e.g. `bootRun`):

```gradle
bootRun {
    jvmArgs = [
            '--add-opens=java.base/java.nio=ALL-UNNAMED',
            '--add-opens=java.base/java.lang=ALL-UNNAMED',
            '-Dio.netty.tryReflectionSetAccessible=true'
    ]
}
```

**Docker** (in your existing service's `Dockerfile` `ENTRYPOINT`/`CMD`, or via `JAVA_TOOL_OPTIONS`):

```dockerfile
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true"
```

**OpenShift** (in the `Deployment`'s container `env`):

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      --add-opens=java.base/java.nio=ALL-UNNAMED
      --add-opens=java.base/java.lang=ALL-UNNAMED
      -Dio.netty.tryReflectionSetAccessible=true
```

## 5. `application.yml`

Add a `data-cache:` block. This is the exact shape used by this repository's own
`src/main/resources/application.yml`, trimmed to a two-dataset example - adjust dataset/query
names and SQL paths to your own:

```yaml
data-cache:
  enabled: true

  startup:
    execution-mode: ${DATA_CACHE_STARTUP_EXECUTION_MODE:ASYNC}
    timeout: 30m

  dremio:
    host: ${DREMIO_HOST:}
    port: ${DREMIO_PORT:32010}
    username: ${DREMIO_USERNAME:}
    password: ${DREMIO_PASSWORD:}
    ssl-enabled: ${DREMIO_SSL_ENABLED:true}
    connect-timeout: 30s
    query-timeout: 30m
    verification-sql: "SELECT 1"

  arrow:
    max-memory: ${DATA_CACHE_ARROW_MAX_MEMORY:2GB}

  duckdb:
    base-directory: ${DATA_CACHE_BASE_DIR:/data/cache}
    temp-directory: ${DATA_CACHE_TEMP_DIR:/data/cache/temp}
    memory-limit: ${DATA_CACHE_DUCKDB_MEMORY_LIMIT:8GB}
    threads: ${DATA_CACHE_DUCKDB_THREADS:8}
    max-versions: 2

  refresh:
    max-concurrent-datasets: 2
    recover-on-startup: true

  pagination:
    default-page-size: 100
    max-page-size: 5000

  # Set to false if you'd rather expose your own endpoints and call DataCacheQueryService/
  # DataCacheRefreshService/DataCacheStatusService directly from your own controllers - see §8.
  api:
    enabled: true

  datasets:
    financial:
      enabled: true
      required: true
      table-name: financial
      source-sql: classpath:datacache/dremio/financial.sql
      startup:
        mode: USE_EXISTING_OR_CREATE
      refresh-cron: "0 0 1,4,7,10,13,16,19 * * *"
      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s
      validation:
        minimum-row-count: 1000000
        required-columns:
          - fiscal_year
          - fiscal_month
          - cost_center
          - actual_amount
          - forecast_amount
        sql: classpath:datacache/validation/financial.sql

  queries:
    financial-summary:
      sql: classpath:datacache/query/financial-summary.sql
      datasets:
        - financial
```

See [03-CONFIGURATION-REFERENCE.md](03-CONFIGURATION-REFERENCE.md) for every available property.

## 6. Environment variables / Secrets

Only four are required for a minimal working setup: `DREMIO_HOST`, `DREMIO_PORT`,
`DREMIO_USERNAME`, `DREMIO_PASSWORD`.

**Local shell:**

```bash
export DREMIO_HOST=dremio.internal.example.com
export DREMIO_PORT=32010
export DREMIO_USERNAME=svc-finance-cache
export DREMIO_PASSWORD='changeme'
```

**Docker (`docker run`):**

```bash
docker run \
  -e DREMIO_HOST=dremio.internal.example.com \
  -e DREMIO_PORT=32010 \
  -e DREMIO_USERNAME=svc-finance-cache \
  -e DREMIO_PASSWORD='changeme' \
  -e DATA_CACHE_BASE_DIR=/data/cache \
  -v finance-cache-data:/data/cache \
  existing-finance-service:latest
```

**OpenShift Secret** (no real credentials shown):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: finance-cache-dremio-credentials
type: Opaque
stringData:
  username: "REPLACE_ME"
  password: "REPLACE_ME"
```

```yaml
# In the Deployment's container env:
env:
  - name: DREMIO_HOST
    value: "dremio.internal.example.com"
  - name: DREMIO_PORT
    value: "32010"
  - name: DREMIO_USERNAME
    valueFrom:
      secretKeyRef:
        name: finance-cache-dremio-credentials
        key: username
  - name: DREMIO_PASSWORD
    valueFrom:
      secretKeyRef:
        name: finance-cache-dremio-credentials
        key: password
```

## 7. Storage / PVC guidance

`data-cache.duckdb.base-directory` (default `/data/cache`) must be a writable directory that
persists across restarts - each dataset's `.duckdb` file and version metadata live there. If it's
empty or missing on startup, the cache auto-creates itself (see [§10](#10-startup-cache-behavior));
losing it just means every dataset rebuilds from Dremio on next start, so it's convenient but not
precious data.

- **Locally:** any writable directory (e.g. `./data/cache`).
- **Docker:** mount a named volume, e.g. `-v finance-cache-data:/data/cache`.
- **OpenShift:** a `PersistentVolumeClaim` mounted at `/data/cache`, owned by the container's
  non-root UID (see `openshift/pvc.yaml` and `openshift/deployment.yaml` in this repository for a
  working example - `RWO` access mode, container `runAsNonRoot` with a matching `fsGroup`).

`data-cache.duckdb.temp-directory` should be on the same volume/filesystem as `base-directory` (it
is used for DuckDB's own spill-to-disk temp files during large loads).

## 8. Spring enablement: `@EnableDataCache`

Add exactly one annotation to your application's `@SpringBootApplication` class (or any other
`@Configuration` class that's part of your context):

```java
package com.company.finance;

import com.enterprise.datacache.feature.cache.EnableDataCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDataCache
public class FinanceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceServiceApplication.class, args);
    }
}
```

That's it - **this is the only Spring wiring change required.** `@EnableDataCache` explicitly
`@Import`s `DataCacheConfiguration`, which registers every cache bean via `@Bean` methods. It does
**not** rely on component-scanning `com.enterprise.datacache` - so it works identically whether
`feature.cache` sits under your own base package or in its own separate namespace inside your
application, and there is no `META-INF/spring/...AutoConfiguration.imports` file to keep in sync.

### REST controllers (optional)

`AdminController` and `QueryController` (both in `feature.cache.api`) are registered by default
when `spring-boot-starter-web` is on the classpath, gated by `data-cache.api.enabled` (default
`true`). If you'd rather expose your own endpoints and call the services below directly from your
existing controllers, set:

```yaml
data-cache:
  api:
    enabled: false
```

and no cache REST controllers are registered at all - only `DataCacheQueryService`,
`DataCacheRefreshService`, and `DataCacheStatusService` beans remain. If you keep them enabled,
they're already in `feature.cache.api`, so they travel with the rest of the copied folder with no
separate copy step.

## 9. Inject and use the services

Three interfaces, already registered as beans once `@EnableDataCache` is in place - inject them
into your own services exactly like any other Spring bean:

```java
package com.company.finance.service;

import com.enterprise.datacache.feature.cache.health.DataCacheStatusService;
import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import com.enterprise.datacache.feature.cache.model.PagedQueryResult;
import com.enterprise.datacache.feature.cache.query.DataCacheQueryService;
import com.enterprise.datacache.feature.cache.refresh.DataCacheRefreshService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FinanceReportingService {

    private final DataCacheQueryService queryService;
    private final DataCacheRefreshService refreshService;
    private final DataCacheStatusService statusService;

    public FinanceReportingService(DataCacheQueryService queryService,
                                    DataCacheRefreshService refreshService,
                                    DataCacheStatusService statusService) {
        this.queryService = queryService;
        this.refreshService = refreshService;
        this.statusService = statusService;
    }

    public PagedQueryResult financialSummary(int fiscalYear, int page, int size) {
        return queryService.execute("financial-summary", Map.of("fiscalYear", fiscalYear), page, size);
    }

    public void triggerFinancialRefresh() {
        refreshService.refreshAsync("financial");
    }

    public DatasetStatus financialStatus() {
        return statusService.getStatus("financial");
    }
}
```

Nothing else needs to be implemented - Dremio integration, Arrow streaming, the DuckDB writer,
metadata, version management, refresh scheduling/retry, validation, startup recovery, the startup
cache lifecycle, and the query engine are all already complete inside the copied folder.

## Query execution flow

```
Your business service
    │  queryService.execute("financial-summary", params, page, size)
    ▼
DataCacheQueryService (feature.cache.query)
    │  looks up "financial-summary" in the query registry
    ▼
QueryRegistry              — resolves registered query name → SQL resource + declared datasets
    │
    ▼
Loads classpath:datacache/query/financial-summary.sql
    │
    ▼
Resolves each declared dataset ("financial") to its currently ACTIVE version
    │
    ▼
DuckDbQueryEngine: ATTACH <dataset>_v<N>.duckdb AS financial (READ_ONLY), ... for every
                   declared dataset, in one isolated DuckDB connection
    │
    ▼
Executes the query SQL with named parameters bound safely (never string-concatenated)
    │
    ▼
Returns a PagedQueryResult (rows, columns, page metadata, dataset versions used)
```

**Dremio SQL vs. DuckDB SQL - do not confuse the two:**

| | Dremio source SQL (`datacache/dremio/*.sql`) | DuckDB query SQL (`datacache/query/*.sql`) |
|---|---|---|
| Runs against | Dremio, via Arrow Flight SQL | DuckDB, locally, against cached files |
| Runs when | Only during a dataset refresh | Every time a query is executed |
| References | Dremio spaces/tables (e.g. `finance.financial_data`) | The dataset's stable logical table name (e.g. `financial`) - never a versioned name or file path |
| Joins across datasets? | No - one dataset per source SQL file | Yes - `financial-summary`/`cfo-summary`-style multi-dataset joins are DuckDB SQL |

## Adding a new dataset - no Java required

Example: adding an `expense` dataset. Two files, zero Java changes.

`src/main/resources/datacache/dremio/expense.sql`:

```sql
-- Dremio source SQL for the "expense" dataset.
SELECT
    fiscal_year,
    fiscal_month,
    cost_center,
    vendor,
    expense_amount
FROM finance.expense_data
```

Add to `application.yml` under `data-cache.datasets`:

```yaml
    expense:
      enabled: true
      required: false
      table-name: expense
      source-sql: classpath:datacache/dremio/expense.sql
      startup:
        mode: USE_EXISTING_OR_CREATE
      refresh-cron: "0 45 3,9,15,21 * * *"
      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s
      validation:
        minimum-row-count: 1
        required-columns:
          - fiscal_year
          - cost_center
          - expense_amount
```

That's the entire change. See [12-ADDING-A-NEW-DATASET.md](12-ADDING-A-NEW-DATASET.md) for more detail.

## Adding a new query - no Java required

Example: `actual-forecast-by-cio`, joining `financial` and `organization`.

`src/main/resources/datacache/query/actual-forecast-by-cio.sql`:

```sql
-- Analytical SQL for the "actual-forecast-by-cio" query.
SELECT
    o.cio,
    SUM(f.actual_amount) AS actual,
    SUM(f.forecast_amount) AS forecast
FROM financial f
JOIN organization o
    ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY o.cio
ORDER BY o.cio
```

Add to `application.yml` under `data-cache.queries`:

```yaml
    actual-forecast-by-cio:
      sql: classpath:datacache/query/actual-forecast-by-cio.sql
      datasets:
        - financial
        - organization
```

Callable immediately: `queryService.execute("actual-forecast-by-cio", Map.of("fiscalYear", 2026), 0, 50)`.
See [13-ADDING-A-NEW-QUERY.md](13-ADDING-A-NEW-QUERY.md) for more detail.

## 10. Startup cache behavior

On every application start, for every **enabled** dataset:

- **No cache exists yet** (fresh volume, or first deploy): the dataset auto-creates itself
  regardless of its configured `startup.mode` - there is no configuration that leaves a dataset
  permanently empty.
- **`startup.mode: USE_EXISTING_OR_CREATE`** and a cache already exists: the existing ACTIVE
  version is reused immediately; no refresh is triggered by startup itself (the dataset's
  `refresh-cron`, if any, still applies on its own schedule).
- **`startup.mode: ALWAYS_REFRESH`** and a cache already exists: the existing ACTIVE version is
  served immediately while a background refresh builds a new version; the old ACTIVE version is
  never deleted until the new one successfully validates and takes over. A refresh that fails
  leaves the existing ACTIVE version fully intact and serving queries.

None of this is destructive: a dataset with a working cache is never dropped or left unavailable
by a restart, a redeploy, or a failed refresh attempt. See
[23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md) for the full state machine,
including `data-cache.startup.execution-mode` (`ASYNC` vs. `BLOCK_UNTIL_REQUIRED_CACHE_READY`) and
readiness-probe integration.

## Minimum Change Summary

| Existing Service Change | Required? |
|---|---|
| Copy `feature/cache/` Java package | Yes |
| Copy `resources/datacache/` SQL folder | Yes |
| Add cache-specific Gradle dependencies (Arrow, DuckDB JDBC, Micrometer) | Yes |
| Add `spring-boot-starter-web`/`-actuator`/`-validation`, `spring-tx` | Only if not already present |
| Add `--add-opens`/Netty JVM args | Yes |
| Add `data-cache.*` block to `application.yml` | Yes |
| Set `DREMIO_HOST`/`PORT`/`USERNAME`/`PASSWORD` | Yes |
| Configure a persistent `data-cache.duckdb.base-directory` | Yes (recommended; falls back to ephemeral storage otherwise) |
| Add `@EnableDataCache` to one `@Configuration` class | Yes - the only Spring wiring change |
| Rename any Java package | No |
| Write Java entities/repositories for a new dataset | No |
| Write Java code for a new query | No |
| Register a `META-INF/spring/...AutoConfiguration.imports` file | No |
| `@EntityScan`/`@EnableJpaRepositories` | No |
| Modify your existing `DataSource`, repositories, services, controllers, or security config | No |
| Copy REST controllers (`feature.cache.api`) | Only if you want them - see `data-cache.api.enabled` |

## Explicit non-interference guarantees

- **DuckDB is never registered as your application's primary `javax.sql.DataSource`.** The cache
  feature manages its own DuckDB connections internally (`DuckDbConnectionFactory` →
  `DuckDBConnection`), opened per-refresh or per-query and closed immediately after. There is no
  `DataSource` bean of any kind registered by `DataCacheConfiguration` - your existing `DataSource`
  (SQL Server, Oracle, Postgres, whatever it is) is left completely alone, and remains the only
  `DataSource` bean in your context.
- **No `@EntityScan` or `@EnableJpaRepositories` is required or added.** The cache feature has no
  JPA entities and no Spring Data repositories - `MetadataStore` talks to its own metadata
  `.duckdb` file with plain JDBC.
- **Cache operations do not depend on your transaction manager.** Nothing in `feature.cache` is
  annotated `@Transactional` against a host-supplied `PlatformTransactionManager`; refresh/version
  safety is handled by the feature's own `RefreshLock` and version-lifecycle bookkeeping, not by
  Spring transactions.
- **`data-cache.enabled: false` fully disables the feature.** Every bean in `DataCacheConfiguration`
  is gated by a class-level `@ConditionalOnProperty(prefix = "data-cache", name = "enabled", havingValue = "true", matchIfMissing = true)`
  - set it to `false` and no scheduler starts, no Dremio connection is attempted, no DuckDB file is
  opened, and no REST controller registers.
- **`data-cache.api.enabled: false` disables only the REST layer** (see [§8](#8-rest-controllers-optional))
  while leaving the three injectable services fully functional.

## Upgrading the cache feature in an existing service

Because all cache Java code lives under `feature/cache/` and nowhere else, and all
business-specific content (which datasets, which queries) lives under `resources/datacache/` and
nowhere else, upgrading to a newer version of the feature is a folder-level operation:

1. **Back up (or just note) your `resources/datacache/*.sql` files and the `data-cache.datasets`/
   `data-cache.queries` blocks in your `application.yml`** - this is your business content and
   must not be lost.
2. Replace `src/main/java/com/enterprise/datacache/feature/cache/` wholesale with the new version.
3. Re-apply your `resources/datacache/*.sql` files and `application.yml` configuration on top (they
   were never touched by step 2).
4. Re-check [03-CONFIGURATION-REFERENCE.md](03-CONFIGURATION-REFERENCE.md) for any new/renamed
   properties introduced by the upgrade before restarting.

This separation - framework Java code in `feature/cache/`, application-specific SQL/YAML in
`resources/datacache/` and your own `application.yml` - exists specifically so a future framework
update can replace the Java folder wholesale without silently overwriting your datasets or queries.

## Migration checklist

- [ ] Copied `src/main/java/com/enterprise/datacache/feature/` into the existing service
- [ ] Copied `src/main/resources/datacache/` into the existing service
- [ ] Added cache-specific Gradle dependencies (Arrow Flight SQL, Arrow vector/memory-netty,
      DuckDB JDBC, Micrometer core, configuration-processor)
- [ ] Confirmed `spring-boot-starter-web`, `-actuator`, `-validation`, and `spring-tx` are present
- [ ] Added `--add-opens=java.base/java.nio=ALL-UNNAMED`,
      `--add-opens=java.base/java.lang=ALL-UNNAMED`, `-Dio.netty.tryReflectionSetAccessible=true`
      wherever the service's JVM args are configured
- [ ] Added the `data-cache:` block to `application.yml` with real dataset/query definitions
- [ ] Set `DREMIO_HOST`/`DREMIO_PORT`/`DREMIO_USERNAME`/`DREMIO_PASSWORD` (env var or Secret)
- [ ] Configured a persistent `data-cache.duckdb.base-directory` (volume or PVC)
- [ ] Added `@EnableDataCache` to the application's `@SpringBootApplication` class
- [ ] Decided on `data-cache.api.enabled` (keep the bundled REST controllers, or call the services
      directly from existing controllers)
- [ ] Injected `DataCacheQueryService`/`DataCacheRefreshService`/`DataCacheStatusService` where needed
- [ ] Started the application and confirmed datasets auto-create on first start
      (`GET /api/v1/cache/admin/datasets`, or `DataCacheStatusService.getAllStatuses()`)
- [ ] Confirmed the existing `DataSource`, repositories, services, controllers, and security
      configuration are unchanged

## Before / after directory tree

**Before:**

```
existing-finance-service/
├── build.gradle
├── src/main/java/com/company/finance/
│   ├── FinanceServiceApplication.java
│   ├── controller/InvoiceController.java
│   ├── service/InvoiceService.java
│   └── repository/InvoiceRepository.java
└── src/main/resources/
    └── application.yml
```

**After:**

```
existing-finance-service/
├── build.gradle                                        # + cache dependencies
├── src/main/java/
│   ├── com/company/finance/
│   │   ├── FinanceServiceApplication.java              # + @EnableDataCache
│   │   ├── controller/InvoiceController.java           # untouched
│   │   ├── service/
│   │   │   ├── InvoiceService.java                     # untouched
│   │   │   └── FinanceReportingService.java             # new: injects DataCacheQueryService, etc.
│   │   └── repository/InvoiceRepository.java           # untouched
│   └── com/enterprise/datacache/feature/cache/          # copied wholesale, unchanged
│       ├── EnableDataCache.java
│       ├── api/            (optional REST controllers)
│       ├── arrow/ benchmark/ config/ dremio/ duckdb/
│       ├── exception/ health/ metadata/ metrics/ model/
│       ├── query/ refresh/ spi/ util/ validation/ version/
└── src/main/resources/
    ├── application.yml                                  # + data-cache.* block
    └── datacache/                                        # copied, then customized
        ├── dremio/financial.sql
        ├── query/financial-summary.sql
        └── validation/financial.sql
```

`InvoiceController`, `InvoiceService`, `InvoiceRepository`, and the existing `DataSource`
configuration are unmodified - the only touched file in the pre-existing `com.company.finance`
tree is the one line adding `@EnableDataCache` to `FinanceServiceApplication`, plus wherever you
choose to inject the cache services.

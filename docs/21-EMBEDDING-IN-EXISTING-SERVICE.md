# 21. Embedding in an Existing Service

`data-cache-core` is a self-contained Spring Boot 3 library. It requires nothing from
`data-cache-app` - no REST controllers, no `DataCacheApplication` main class, no standalone
`Dockerfile`, no OpenShift `Deployment`. This is proven directly by
`EmbeddingAcceptanceTest` (`data-cache-core/src/test/java/.../embedding/`), which boots a
completely minimal `@SpringBootApplication` that depends on nothing but `data-cache-core`, and
exercises the real production DuckDB writer, metadata store, refresh coordinator, version manager,
and query engine end-to-end.

## Option A: Gradle module/JAR dependency (preferred)

In your existing Spring Boot application's `build.gradle`:

```gradle
dependencies {
    implementation project(':data-cache-core')   // same-repo multi-module build
    // or, once published to a Maven repository:
    // implementation 'com.enterprise.datacache:data-cache-core:0.1.0'
}
```

That's the entire dependency change. `data-cache-core`'s own `build.gradle` already pulls in
everything it needs transitively: `spring-boot-autoconfigure`, `spring-context`, Micrometer,
Arrow Flight SQL, and the DuckDB JDBC driver (all declared `api`, so they're visible to your
application too).

### `application.yml`

Add the `data-cache.*` block from [03-CONFIGURATION-REFERENCE.md](03-CONFIGURATION-REFERENCE.md) -
Dremio credentials, DuckDB storage path, your dataset and query definitions - to your existing
application's configuration. `DataCacheAutoConfiguration` is picked up automatically via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` - no
`@Import` or `@ComponentScan` change needed.

### SQL files

Put your Dremio source SQL and DuckDB analytical query SQL anywhere resolvable as a Spring
resource - typically `src/main/resources/datacache/dremio/*.sql` and
`src/main/resources/datacache/query/*.sql` in your existing application's module, referenced by
`classpath:datacache/...` in configuration exactly as in `data-cache-app`.

### Inject the services

```java
@Service
public class FinanceService {

    private final DataCacheQueryService queryService;
    private final DataCacheRefreshService refreshService;
    private final DataCacheStatusService statusService;

    public FinanceService(DataCacheQueryService queryService,
                           DataCacheRefreshService refreshService,
                           DataCacheStatusService statusService) {
        this.queryService = queryService;
        this.refreshService = refreshService;
        this.statusService = statusService;
    }
}
```

Nothing else is required - Dremio integration, Arrow streaming, the DuckDB writer, metadata,
version management, refresh scheduling/retry, validation, startup recovery, the mandatory startup
cache lifecycle (`DataCacheStartupCoordinator` - see
[23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md)), and the query engine are already
complete. You are not expected to (and should not need to) implement any of that yourself. If the
host application already exposes an Actuator readiness probe, add
`data-cache.startup.execution-mode=BLOCK_UNTIL_REQUIRED_CACHE_READY` and
`management.endpoint.health.group.readiness.include=readinessState,dataCacheReadiness` to gate it
on required datasets being ready; otherwise the default `ASYNC` mode needs no readiness wiring at
all.

## Option B: copy-source approach

If a Gradle module/JAR dependency isn't feasible, copy the following into your existing
application (same package names, to keep the auto-configuration import file valid):

```
data-cache-core/src/main/java/com/enterprise/datacache/**      -> your src/main/java
data-cache-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
                                                                  -> your src/main/resources/META-INF/spring/
```

Add the Gradle dependencies from `data-cache-core/build.gradle`'s `dependencies {}` block (Arrow
Flight SQL, Arrow vector/memory-netty, DuckDB JDBC, Micrometer core, spring-boot-autoconfigure) to
your existing `build.gradle`. Then configure and inject exactly as in Option A.

## What is explicitly NOT required

- `data-cache-app` module or any of its source
- REST controllers (`QueryController`, `AdminController`) or their DTOs
- The standalone `DataCacheApplication` main class
- The standalone `Dockerfile`
- The `openshift/*.yaml` manifests

These exist only to make `data-cache-core` independently runnable/testable as a service; none of
them are part of the reusable library's dependency graph (enforced by `ArchitectureTest`, which
asserts `data-cache-core` never depends on `..datacache.app..` or Spring MVC packages).

## Actuator health indicators are optional

`DataCacheHealthIndicator`/`DremioSourceHealthIndicator` are annotated
`@ConditionalOnClass(HealthIndicator.class)` - if your existing application doesn't already depend
on `spring-boot-starter-actuator`, these two beans simply don't register; everything else in
`data-cache-core` works identically either way.

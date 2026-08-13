# 21. Embedding in an Existing Service

This is now a full, dedicated guide - see
**[INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md](INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md)**.

The short version: the entire cache implementation lives in one Java package
(`com.enterprise.datacache.feature.cache`) and one resources folder (`src/main/resources/datacache/`).
Copy both into an existing Spring Boot 3 application, add the cache-specific Gradle dependencies,
add a `data-cache.*` block to `application.yml`, and add exactly one annotation -
`@EnableDataCache` - to the host application's `@SpringBootApplication` class. No package rename,
no `META-INF/spring/...AutoConfiguration.imports` file, no `@EntityScan`, and no changes to the
host's existing `DataSource`, repositories, services, or controllers are required.

`ExistingServiceEmbeddingTest` (`src/test/java/com/example/hostapp/`) proves this end to end: a
minimal `@SpringBootApplication` in a completely unrelated test package adds `@EnableDataCache`
and gets `DataCacheQueryService`/`DataCacheRefreshService`/`DataCacheStatusService` fully wired,
while its own unrelated bean and its own `DataSource` are left untouched.

package com.enterprise.datacache;

import com.enterprise.datacache.feature.cache.EnableDataCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone demo application exposing the {@code feature.cache} package over HTTP. This class
 * contains no cache logic of its own - it exists only to run the feature standalone for local
 * development, testing, and the OpenShift deployment example. Every endpoint is a thin adapter
 * (in {@code feature.cache.api}) over {@code DataCacheQueryService}, {@code DataCacheRefreshService},
 * and {@code DataCacheStatusService}.
 *
 * <p>The entire cache implementation lives under {@code com.enterprise.datacache.feature.cache} -
 * a single, self-contained, portable Java package that can be copied into any existing Spring Boot
 * 3 application and enabled there the same way it is enabled here: with {@link EnableDataCache}.
 * See docs/INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md.
 */
@SpringBootApplication
@EnableDataCache
public class DataCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataCacheApplication.class, args);
    }
}

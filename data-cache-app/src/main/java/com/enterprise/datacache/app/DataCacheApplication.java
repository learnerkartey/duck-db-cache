package com.enterprise.datacache.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone REST application exposing {@code data-cache-core} over HTTP. Contains no cache logic
 * of its own - every endpoint is a thin adapter over {@code DataCacheQueryService},
 * {@code DataCacheRefreshService}, and {@code DataCacheStatusService}, which are wired entirely by
 * {@code DataCacheAutoConfiguration} in the core module.
 */
@SpringBootApplication
public class DataCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataCacheApplication.class, args);
    }
}

package com.example.hostapp;

import com.enterprise.datacache.feature.cache.EnableDataCache;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stand-in for a completely unrelated, pre-existing Spring Boot application (a different base
 * package, its own beans and {@code DataSource}) that embeds the {@code feature.cache} package by
 * adding a single {@link EnableDataCache} annotation - the same mechanism documented in
 * docs/INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md. No component scanning of
 * {@code com.enterprise.datacache} is relied upon: {@link EnableDataCache} imports every cache bean
 * explicitly, so it works from a base package that shares no ancestry with the cache feature.
 */
@SpringBootApplication
@EnableDataCache
public class HostApplication {
}

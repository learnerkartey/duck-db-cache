package com.enterprise.datacache.feature.cache;

import com.enterprise.datacache.feature.cache.config.DataCacheConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Add to any {@code @Configuration} (typically the host application's {@code @SpringBootApplication}
 * class) to enable the entire data cache feature: Dremio Arrow Flight SQL ingestion, DuckDB storage
 * and query engine, version lifecycle, refresh scheduling, startup cache lifecycle, and (unless
 * {@code data-cache.api.enabled=false}) the REST controllers in {@code feature.cache.api}.
 *
 * <p>This is the only wiring change required to embed the {@code feature.cache} package in an
 * existing Spring Boot 3 application - no component scanning of this package is relied upon, so it
 * works whether {@code feature.cache} is copied under the host's own base package or left under
 * {@code com.enterprise.datacache.feature.cache} inside an otherwise unrelated application. See
 * docs/INTEGRATE-INTO-EXISTING-SPRING-BOOT-SERVICE.md for the full integration guide.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(DataCacheConfiguration.class)
public @interface EnableDataCache {
}

package com.enterprise.datacache.util;

import com.enterprise.datacache.exception.InvalidConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Resolves SQL text from a Spring resource location such as {@code classpath:datacache/dremio/financial.sql}
 * or {@code file:/etc/data-cache/queries/financial.sql}. Used for both Dremio source SQL and DuckDB
 * analytical/validation SQL - the resource location syntax is identical, only the directory convention differs.
 */
public class SqlResourceLoader {

    private final ResourceLoader resourceLoader;

    public SqlResourceLoader() {
        this(new DefaultResourceLoader());
    }

    public SqlResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String location) {
        if (!StringUtils.hasText(location)) {
            throw new InvalidConfigurationException("SQL resource location must not be blank");
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new InvalidConfigurationException("SQL resource not found: " + location);
        }
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidConfigurationException("Failed to read SQL resource: " + location, e);
        }
        if (!StringUtils.hasText(content)) {
            throw new InvalidConfigurationException("SQL resource is empty: " + location);
        }
        return content.trim();
    }
}

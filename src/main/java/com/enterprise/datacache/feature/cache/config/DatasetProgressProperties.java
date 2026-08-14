package com.enterprise.datacache.feature.cache.config;

/**
 * Optional per-dataset hint used only to compute an {@code estimatedPercent} in progress logs and
 * the status API: {@code data-cache.datasets.<name>.progress.expected-row-count}. Never used for
 * anything else - refresh behavior, validation, and retention are completely unaffected by this
 * value. When unset, {@code estimatedPercent} falls back to the previous ACTIVE version's row
 * count (if any) as a rougher estimate; when neither is available, no percentage is reported.
 */
public class DatasetProgressProperties {

    private Long expectedRowCount;

    public Long getExpectedRowCount() {
        return expectedRowCount;
    }

    public void setExpectedRowCount(Long expectedRowCount) {
        this.expectedRowCount = expectedRowCount;
    }
}

package com.enterprise.datacache.feature.cache.config;

import org.springframework.util.unit.DataSize;

/** Arrow off-heap memory management settings. */
public class ArrowProperties {

    /** Hard cap on the root {@code BufferAllocator}. Every Flight stream uses a child allocator bounded by this. */
    private DataSize maxMemory = DataSize.ofGigabytes(2);

    public DataSize getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(DataSize maxMemory) {
        this.maxMemory = maxMemory;
    }
}

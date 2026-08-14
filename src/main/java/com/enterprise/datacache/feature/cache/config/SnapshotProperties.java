package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.model.SnapshotMode;

/**
 * How a resumable dataset binds every chunk (including resumed ones) to the same logical source
 * state: {@code data-cache.datasets.<name>.resume.snapshot.*}.
 */
public class SnapshotProperties {

    private SnapshotMode mode = SnapshotMode.NONE;

    /**
     * Named parameter the dataset's source SQL references (e.g. {@code :cacheAsOf}) that the
     * framework binds to the timestamp captured when the refresh first started. Required when
     * {@code mode: AS_OF_VALUE}.
     */
    private String parameterName;

    public SnapshotMode getMode() {
        return mode;
    }

    public void setMode(SnapshotMode mode) {
        this.mode = mode;
    }

    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }
}

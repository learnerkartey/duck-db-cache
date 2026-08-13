package com.enterprise.datacache.feature.cache.exception;

/** Raised when a caller references a dataset name that is not configured (or not enabled). */
public class DatasetNotFoundException extends DataCacheException {

    public DatasetNotFoundException(String datasetName) {
        super("DATASET_NOT_FOUND", "No enabled dataset configured with name '" + datasetName + "'", false);
    }
}

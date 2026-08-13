package com.enterprise.datacache.feature.cache.exception;

import com.enterprise.datacache.feature.cache.model.DatasetAvailability;

/**
 * Raised by the query engine when a query references a configured, enabled dataset that has no
 * ACTIVE version yet. Distinguishes "still loading for the first time" from "unavailable" (a
 * prior load failed, or none was ever attempted) so callers - and REST clients - can tell the
 * difference between "try again shortly" and "something is broken".
 */
public class DatasetNotAvailableException extends DataCacheException {

    private final DatasetAvailability availability;

    public DatasetNotAvailableException(String datasetName, DatasetAvailability availability) {
        super("DATASET_NOT_AVAILABLE", message(datasetName, availability), false);
        this.availability = availability;
    }

    private static String message(String datasetName, DatasetAvailability availability) {
        return switch (availability) {
            case STARTUP_LOADING -> "Dataset '" + datasetName
                    + "' has no ACTIVE version yet - its initial startup load is currently in progress. Try again shortly.";
            case UNAVAILABLE -> "Dataset '" + datasetName
                    + "' has no ACTIVE version and nothing is currently loading it - the last load either failed or "
                    + "was never attempted. Check its status and trigger a refresh.";
            case AVAILABLE -> throw new IllegalArgumentException(
                    "DatasetNotAvailableException must not be constructed for an AVAILABLE dataset");
        };
    }

    public DatasetAvailability availability() {
        return availability;
    }
}

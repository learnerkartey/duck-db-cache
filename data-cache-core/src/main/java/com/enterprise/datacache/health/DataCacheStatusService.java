package com.enterprise.datacache.health;

import com.enterprise.datacache.model.DatasetStatus;
import java.util.Map;

/** Read-only status reporting for every configured dataset - active/previous version, row counts, last refresh outcome. */
public interface DataCacheStatusService {

    DatasetStatus getStatus(String datasetName);

    Map<String, DatasetStatus> getAllStatuses();
}

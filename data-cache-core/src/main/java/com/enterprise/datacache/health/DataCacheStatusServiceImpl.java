package com.enterprise.datacache.health;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.exception.DatasetNotFoundException;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.model.DatasetStatus;
import com.enterprise.datacache.model.DatasetVersion;
import com.enterprise.datacache.model.RefreshOutcome;
import com.enterprise.datacache.model.VersionState;
import com.enterprise.datacache.refresh.RefreshLock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DataCacheStatusServiceImpl implements DataCacheStatusService {

    private final DataCacheProperties properties;
    private final MetadataStore metadataStore;
    private final RefreshLock refreshLock;

    public DataCacheStatusServiceImpl(DataCacheProperties properties, MetadataStore metadataStore,
            RefreshLock refreshLock) {
        this.properties = properties;
        this.metadataStore = metadataStore;
        this.refreshLock = refreshLock;
    }

    @Override
    public DatasetStatus getStatus(String datasetName) {
        DatasetProperties config = properties.getDatasets().get(datasetName);
        if (config == null) {
            throw new DatasetNotFoundException(datasetName);
        }

        List<DatasetVersion> versions = metadataStore.findAll(datasetName); // ordered version DESC
        Optional<DatasetVersion> active = versions.stream().filter(v -> v.state() == VersionState.ACTIVE).findFirst();
        Optional<DatasetVersion> previous = versions.stream().filter(v -> v.state() == VersionState.PREVIOUS).findFirst();
        Optional<DatasetVersion> mostRecentAttempt = versions.stream().findFirst();

        RefreshOutcome lastStatus = mostRecentAttempt.map(this::classify).orElse(RefreshOutcome.NEVER_RUN);
        String lastError = mostRecentAttempt
                .filter(v -> v.state() == VersionState.FAILED)
                .map(DatasetVersion::errorSummary)
                .orElse(null);

        return new DatasetStatus(
                datasetName,
                config.isEnabled(),
                active.map(DatasetVersion::version).orElse(null),
                previous.map(DatasetVersion::version).orElse(null),
                active.map(DatasetVersion::rowCount).orElse(null),
                active.map(DatasetVersion::completedAt).orElse(null),
                lastStatus,
                mostRecentAttempt.map(DatasetVersion::durationMs).orElse(null),
                lastError,
                refreshLock.isRunning(datasetName));
    }

    private RefreshOutcome classify(DatasetVersion version) {
        return switch (version.state()) {
            case ACTIVE, PREVIOUS -> RefreshOutcome.SUCCESS;
            case FAILED -> "VALIDATION_FAILED".equals(version.errorCode()) ? RefreshOutcome.VALIDATION_FAILED : RefreshOutcome.FAILED;
            case BUILDING, VALIDATING -> RefreshOutcome.FAILED;
        };
    }

    @Override
    public Map<String, DatasetStatus> getAllStatuses() {
        Map<String, DatasetStatus> result = new LinkedHashMap<>();
        for (String name : properties.getDatasets().keySet()) {
            result.put(name, getStatus(name));
        }
        return result;
    }
}

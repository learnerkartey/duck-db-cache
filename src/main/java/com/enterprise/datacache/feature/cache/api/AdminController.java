package com.enterprise.datacache.feature.cache.api;

import com.enterprise.datacache.feature.cache.health.DataCacheStatusService;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.DatasetStatus;
import com.enterprise.datacache.feature.cache.model.RefreshTrigger;
import com.enterprise.datacache.feature.cache.refresh.DataCacheRefreshService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative endpoints for triggering refreshes and inspecting dataset status. This
 * controller only calls the public cache services - all refresh/version/lock logic lives
 * elsewhere in this feature package, never here.
 */
@RestController
@RequestMapping("/api/v1/cache/admin")
public class AdminController {

    private final DataCacheRefreshService refreshService;
    private final DataCacheStatusService statusService;

    public AdminController(DataCacheRefreshService refreshService, DataCacheStatusService statusService) {
        this.refreshService = refreshService;
        this.statusService = statusService;
    }

    @PostMapping("/datasets/{datasetName}/refresh")
    public DatasetRefreshResult refreshDataset(@PathVariable String datasetName) {
        return refreshService.refresh(datasetName, RefreshTrigger.MANUAL);
    }

    /**
     * Explicitly resumes a paused/interrupted BUILDING version from its last completed chunk.
     * Automatic startup resume never requires this to be called - it exists purely so an operator
     * can explicitly continue a known paused build (see {@code status=PAUSED_RETRYABLE} on
     * {@link #getDataset}) rather than issuing the more general {@code /refresh}.
     */
    @PostMapping("/datasets/{datasetName}/resume")
    public DatasetRefreshResult resumeDataset(@PathVariable String datasetName) {
        return refreshService.resume(datasetName);
    }

    /**
     * Intentionally abandons any partial BUILDING version for this dataset and starts a brand-new
     * version from zero, even if the partial build would otherwise be safely resumable. The current
     * ACTIVE version is never touched. Protected admin operation - use only when a partial build is
     * known to be undesirable to continue (e.g. built against a now-known-bad source state).
     */
    @PostMapping("/datasets/{datasetName}/restart-refresh")
    public DatasetRefreshResult restartRefresh(@PathVariable String datasetName) {
        return refreshService.restartRefresh(datasetName);
    }

    @PostMapping("/refresh-all")
    public Map<String, DatasetRefreshResult> refreshAll() {
        return refreshService.refreshAll();
    }

    @GetMapping("/datasets")
    public Map<String, DatasetStatus> listDatasets() {
        return statusService.getAllStatuses();
    }

    @GetMapping("/datasets/{datasetName}")
    public DatasetStatus getDataset(@PathVariable String datasetName) {
        return statusService.getStatus(datasetName);
    }
}

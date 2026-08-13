package com.enterprise.datacache.app.controller;

import com.enterprise.datacache.health.DataCacheStatusService;
import com.enterprise.datacache.model.DatasetRefreshResult;
import com.enterprise.datacache.model.DatasetStatus;
import com.enterprise.datacache.refresh.DataCacheRefreshService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative endpoints for triggering refreshes and inspecting dataset status. Controllers
 * only call core facades - all refresh/version/lock logic lives in {@code data-cache-core}.
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
        return refreshService.refresh(datasetName);
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

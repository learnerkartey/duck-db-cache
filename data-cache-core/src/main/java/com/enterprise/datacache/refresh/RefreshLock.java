package com.enterprise.datacache.refresh;

import java.util.concurrent.ConcurrentHashMap;

/** Per-dataset mutual exclusion: at most one refresh of a given dataset may run at a time. */
public class RefreshLock {

    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    public boolean tryAcquire(String datasetName) {
        return running.putIfAbsent(datasetName, Boolean.TRUE) == null;
    }

    public void release(String datasetName) {
        running.remove(datasetName);
    }

    public boolean isRunning(String datasetName) {
        return running.containsKey(datasetName);
    }
}

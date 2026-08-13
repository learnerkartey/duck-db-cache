package com.enterprise.datacache.feature.cache.version;

/** Identifies one physical dataset version for reader-reference-counting purposes. */
record VersionKey(String datasetName, long version) {
}

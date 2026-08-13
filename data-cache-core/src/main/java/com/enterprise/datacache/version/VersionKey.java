package com.enterprise.datacache.version;

/** Identifies one physical dataset version for reader-reference-counting purposes. */
record VersionKey(String datasetName, long version) {
}

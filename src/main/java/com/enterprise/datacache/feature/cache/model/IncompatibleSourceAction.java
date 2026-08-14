package com.enterprise.datacache.feature.cache.model;

/**
 * What to do when a BUILDING version cannot be safely resumed (source SQL changed, source schema
 * changed, snapshot binding unavailable, or the partial build is too old/corrupt).
 */
public enum IncompatibleSourceAction {
    /** Abandon the incompatible partial build and start a brand-new BUILDING version from chunk 1 -
     *  the only currently supported action. The previous ACTIVE version is never affected. */
    RESTART_NEW_VERSION
}

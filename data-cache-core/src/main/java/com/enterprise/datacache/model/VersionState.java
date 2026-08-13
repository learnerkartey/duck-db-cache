package com.enterprise.datacache.model;

/**
 * Lifecycle state of a single physical dataset version.
 *
 * <pre>
 * BUILDING -&gt; VALIDATING -&gt; ACTIVE -&gt; PREVIOUS -&gt; (deleted)
 *                        \-&gt; FAILED
 * </pre>
 */
public enum VersionState {
    BUILDING,
    VALIDATING,
    ACTIVE,
    PREVIOUS,
    FAILED
}

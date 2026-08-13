package com.enterprise.datacache.feature.cache.config;

/**
 * Per-dataset startup behavior.
 *
 * <p>Only two modes are exposed, deliberately. An earlier three-mode design
 * (REUSE_EXISTING / REFRESH_IF_MISSING / ALWAYS_REFRESH) was considered, but
 * REUSE_EXISTING and REFRESH_IF_MISSING turn out to specify identical behavior once the
 * mandatory "auto-create when no cache exists" rule is applied to both: an existing ACTIVE
 * version is always reused, and a missing one is always loaded automatically either way. Keeping
 * both would only give operators two names for the same thing, so they are collapsed into
 * {@link #USE_EXISTING_OR_CREATE}. See docs/23-STARTUP-CACHE-LIFECYCLE.md for the full rationale.
 */
public enum StartupMode {

    /**
     * If a valid ACTIVE version already exists, reuse it immediately and do not refresh just
     * because the application (re)started - the configured {@code refresh-cron} (or a manual
     * trigger) will refresh it later, on its own schedule.
     *
     * <p>If no ACTIVE version exists yet, this mode still automatically triggers an initial load
     * - a dataset is never left permanently empty simply because no one has called the refresh
     * API yet. This is the safe default for most datasets.
     */
    USE_EXISTING_OR_CREATE,

    /**
     * If a valid ACTIVE version already exists, it is made available to queries immediately -
     * exactly like {@link #USE_EXISTING_OR_CREATE} - and a new version is additionally built in
     * the background through the normal refresh pipeline, cutting over atomically once it
     * validates. The existing ACTIVE version is never deleted or made unavailable before its
     * replacement is ready; if the background refresh fails, the existing version simply remains
     * ACTIVE.
     *
     * <p>If no ACTIVE version exists yet, behavior is identical to {@link #USE_EXISTING_OR_CREATE}
     * - an initial load is triggered (there is nothing to "keep serving" while it loads).
     */
    ALWAYS_REFRESH
}

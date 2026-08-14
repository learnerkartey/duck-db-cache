package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.model.IncompatibleSourceAction;
import java.time.Duration;

/** Global resumable-refresh settings: {@code data-cache.resume.*}. */
public class GlobalResumeProperties {

    /** Master switch for the resumable-refresh subsystem. When false, every dataset behaves as
     *  though {@code resume.enabled: false} regardless of its own setting. */
    private boolean enabled = true;

    /** A BUILDING version older than this is not resumed - a fresh version is started instead. */
    private Duration maxResumeAge = Duration.ofHours(24);

    private IncompatibleSourceAction onIncompatibleSource = IncompatibleSourceAction.RESTART_NEW_VERSION;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMaxResumeAge() {
        return maxResumeAge;
    }

    public void setMaxResumeAge(Duration maxResumeAge) {
        this.maxResumeAge = maxResumeAge;
    }

    public IncompatibleSourceAction getOnIncompatibleSource() {
        return onIncompatibleSource;
    }

    public void setOnIncompatibleSource(IncompatibleSourceAction onIncompatibleSource) {
        this.onIncompatibleSource = onIncompatibleSource;
    }
}

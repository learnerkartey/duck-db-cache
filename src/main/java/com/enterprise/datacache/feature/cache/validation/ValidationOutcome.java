package com.enterprise.datacache.feature.cache.validation;

import java.util.List;

/** Result of running all configured validation rules against a BUILDING dataset version. */
public record ValidationOutcome(boolean passed, List<String> failureReasons, List<CheckResult> checks) {

    /** One named, configured validation rule's outcome - logged as a single concise line, never per-value. */
    public record CheckResult(String name, boolean passed, String detail) {
    }

    public static ValidationOutcome pass(List<CheckResult> checks) {
        return new ValidationOutcome(true, List.of(), checks);
    }

    public static ValidationOutcome fail(List<String> reasons, List<CheckResult> checks) {
        return new ValidationOutcome(false, reasons, checks);
    }

    public String summarize() {
        return String.join("; ", failureReasons);
    }
}

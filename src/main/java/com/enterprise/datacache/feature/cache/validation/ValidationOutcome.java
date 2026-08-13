package com.enterprise.datacache.feature.cache.validation;

import java.util.List;

/** Result of running all configured validation rules against a BUILDING dataset version. */
public record ValidationOutcome(boolean passed, List<String> failureReasons) {

    public static ValidationOutcome pass() {
        return new ValidationOutcome(true, List.of());
    }

    public static ValidationOutcome fail(List<String> reasons) {
        return new ValidationOutcome(false, reasons);
    }

    public String summarize() {
        return String.join("; ", failureReasons);
    }
}

package com.enterprise.datacache.feature.cache.resume;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Substitutes a single {@code :parameterName} occurrence in a dataset's source SQL with a
 * safely-formatted {@code TIMESTAMP} literal of the snapshot instant captured once when a
 * resumable refresh (first) started - the same value is bound into every chunk, original or
 * resumed, so the whole refresh logically reads the source "as of" one consistent instant. See
 * {@code data-cache.datasets.&lt;name&gt;.resume.snapshot.*} and
 * docs/RESUMABLE-REFRESH-AND-RECOVERY.md#source-consistency.
 */
public final class SnapshotParameterBinder {

    private static final DateTimeFormatter LITERAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private SnapshotParameterBinder() {
    }

    public static String bind(String sourceSql, String parameterName, Instant snapshotValue) {
        String token = ":" + parameterName;
        String literal = "TIMESTAMP '" + LITERAL_FORMAT.format(snapshotValue) + "'";
        return sourceSql.replace(token, literal);
    }
}

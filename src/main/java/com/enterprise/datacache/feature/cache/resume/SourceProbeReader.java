package com.enterprise.datacache.feature.cache.resume;

import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import org.apache.arrow.vector.VectorSchemaRoot;

/** Executes a small, single-row probe query (e.g. {@code MIN}/{@code MAX}) used to plan chunks - never the dataset's actual rows. */
final class SourceProbeReader {

    private SourceProbeReader() {
    }

    /** @return the first row's column values, or {@code null} if the probe returned zero rows (an empty source). */
    static Object[] readSingleRow(DremioSource source, String probeSql, int expectedColumns) {
        try (ArrowBatchStream stream = source.executeQuery(probeSql)) {
            while (stream.loadNextBatch()) {
                VectorSchemaRoot batch = stream.currentBatch();
                if (batch.getRowCount() > 0) {
                    Object[] result = new Object[expectedColumns];
                    for (int c = 0; c < expectedColumns; c++) {
                        result[c] = batch.getVector(c).getObject(0);
                    }
                    return result;
                }
            }
            return null;
        } catch (RuntimeException e) {
            throw new DremioSourceException("Resume chunk-planning probe query failed: " + e.getMessage(), true, e);
        }
    }
}

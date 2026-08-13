package com.enterprise.datacache.feature.cache.spi;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Source-agnostic stream of Arrow record batches produced by executing a single query against a
 * {@link DremioSource}. Mirrors the shape of {@code org.apache.arrow.flight.FlightStream} so the
 * production implementation is a thin adapter, while test/embedding implementations can supply
 * synthetic batches without any network dependency.
 *
 * <p>Ownership: the batch returned by {@link #currentBatch()} is owned by the stream and is only
 * valid until the next call to {@link #loadNextBatch()} or {@link #close()}. Callers must not
 * retain a reference to it across batches.
 */
public interface ArrowBatchStream extends AutoCloseable {

    /** Schema shared by every batch produced by this stream. */
    Schema schema();

    /**
     * Advances to the next batch, blocking until it is available.
     *
     * @return {@code true} if a new batch is now available via {@link #currentBatch()},
     *         {@code false} if the stream is exhausted.
     */
    boolean loadNextBatch();

    /** The batch made current by the most recent {@link #loadNextBatch()} call. */
    VectorSchemaRoot currentBatch();

    /** Releases all resources (native buffers, network streams) held by this stream. */
    @Override
    void close();
}

package com.enterprise.datacache.feature.cache.spi;

/**
 * Boundary abstraction over the external Dremio Arrow Flight SQL source. The production
 * implementation ({@code com.enterprise.datacache.feature.cache.dremio.DremioFlightSqlSource}) opens a real
 * Flight SQL connection; alternate implementations (used only in tests, never in
 * {@code src/main}) can supply synthetic data so that the refresh/version/query pipeline can be
 * exercised - and embedded into a host application - without a live Dremio cluster.
 */
public interface DremioSource extends AutoCloseable {

    /**
     * Executes {@code sql} against the source and returns a stream of Arrow record batches.
     * Implementations must stream results with bounded memory - they must not buffer the full
     * result set.
     *
     * @param sql the SQL text to execute (verbatim, already resolved from its resource location)
     * @return an open {@link ArrowBatchStream}; the caller owns it and must close it
     */
    ArrowBatchStream executeQuery(String sql);

    /** A cheap connectivity probe used for health checks. Should not run a full query. */
    boolean isHealthy();

    @Override
    void close();
}

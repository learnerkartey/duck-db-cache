package com.enterprise.datacache.feature.cache.dremio;

import com.enterprise.datacache.feature.cache.exception.DremioSourceException;
import com.enterprise.datacache.feature.cache.spi.ArrowBatchStream;
import java.util.Iterator;
import java.util.List;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iterates every {@link FlightEndpoint} returned in a {@link FlightInfo}, opening one
 * {@link FlightStream} per endpoint ticket in turn and exposing a single logical batch sequence.
 * Dremio commonly returns a single endpoint, but a correct client must not assume that.
 */
final class DremioArrowBatchStream implements ArrowBatchStream {

    private static final Logger log = LoggerFactory.getLogger(DremioArrowBatchStream.class);

    private final FlightSqlClient client;
    private final Schema schema;
    private final CallOption[] callOptions;
    private final Iterator<FlightEndpoint> endpoints;

    private FlightStream currentStream;

    DremioArrowBatchStream(FlightSqlClient client, FlightInfo flightInfo, CallOption[] callOptions) {
        this.client = client;
        this.schema = flightInfo.getSchema();
        this.callOptions = callOptions;
        List<FlightEndpoint> endpointList = flightInfo.getEndpoints();
        this.endpoints = endpointList.iterator();
        log.debug("event=dremio-stream-opened endpointCount={}", endpointList.size());
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public boolean loadNextBatch() {
        while (true) {
            if (currentStream == null) {
                if (!endpoints.hasNext()) {
                    return false;
                }
                currentStream = openStream(endpoints.next());
            }
            try {
                if (currentStream.next()) {
                    return true;
                }
            } catch (FlightRuntimeException e) {
                throw new DremioSourceException("Arrow Flight stream failed while reading batches: "
                        + e.status().description(), DremioFlightSqlSource.isRetryable(e), e);
            }
            // Current endpoint exhausted; close it and move to the next one.
            closeQuietly(currentStream);
            currentStream = null;
        }
    }

    private FlightStream openStream(FlightEndpoint endpoint) {
        try {
            // Endpoints with no explicit location reuse this same server connection, which is the
            // common case for Dremio. Endpoints that do specify a location still resolve to a
            // ticket redeemable on the same coordinator in Dremio's Flight SQL implementation, so
            // we always redeem through the already-authenticated client.
            return client.getStream(endpoint.getTicket(), callOptions);
        } catch (FlightRuntimeException e) {
            throw new DremioSourceException("Failed to open Flight stream for endpoint: " + e.status().description(),
                    DremioFlightSqlSource.isRetryable(e), e);
        }
    }

    @Override
    public VectorSchemaRoot currentBatch() {
        if (currentStream == null) {
            throw new IllegalStateException("loadNextBatch() must return true before currentBatch() is called");
        }
        return currentStream.getRoot();
    }

    @Override
    public void close() {
        closeQuietly(currentStream);
        currentStream = null;
    }

    private void closeQuietly(FlightStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception e) {
            log.warn("event=dremio-stream-close-failed", e);
        }
    }
}

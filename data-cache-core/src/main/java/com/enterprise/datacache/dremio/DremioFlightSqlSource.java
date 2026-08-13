package com.enterprise.datacache.dremio;

import com.enterprise.datacache.config.DremioProperties;
import com.enterprise.datacache.exception.DremioSourceException;
import com.enterprise.datacache.spi.ArrowBatchStream;
import com.enterprise.datacache.spi.DremioSource;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.CallOptions;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicAuthCredentialWriter;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link DremioSource} backed by a real Apache Arrow Flight SQL connection to Dremio.
 *
 * <p>Lifecycle: one {@link FlightClient}/{@link FlightSqlClient} pair is opened lazily on first
 * use and reused for the lifetime of this bean (across scheduled refreshes), authenticated once
 * via the Arrow Flight basic-auth handshake. All Arrow buffers it allocates come from a single
 * child allocator carved out of the shared root allocator, bounded by
 * {@code data-cache.arrow.max-memory}.
 */
public class DremioFlightSqlSource implements DremioSource {

    private static final Logger log = LoggerFactory.getLogger(DremioFlightSqlSource.class);

    private final DremioProperties properties;
    private final BufferAllocator allocator;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile FlightClient flightClient;
    private volatile FlightSqlClient flightSqlClient;
    private volatile CredentialCallOption authOption;

    public DremioFlightSqlSource(DremioProperties properties, BufferAllocator rootAllocator) {
        this.properties = properties;
        this.allocator = rootAllocator.newChildAllocator("dremio-flight-client", 0, rootAllocator.getLimit());
    }

    @Override
    public ArrowBatchStream executeQuery(String sql) {
        ensureConnected();
        FlightInfo flightInfo;
        try {
            flightInfo = flightSqlClient.execute(sql, callOptions());
        } catch (FlightRuntimeException e) {
            throw new DremioSourceException("Dremio rejected query execution: " + e.status().description(),
                    isRetryable(e), e);
        } catch (RuntimeException e) {
            throw new DremioSourceException("Failed to execute Dremio query", true, e);
        }
        if (flightInfo.getEndpoints().isEmpty()) {
            throw new DremioSourceException("Dremio returned zero Flight endpoints for the query", false);
        }
        return new DremioArrowBatchStream(flightSqlClient, flightInfo, callOptions());
    }

    @Override
    public boolean isHealthy() {
        try {
            ensureConnected();
            try (ArrowBatchStream stream = executeQuery(properties.getVerificationSql())) {
                stream.loadNextBatch();
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("event=dremio-health-check-failed reason={}", e.getMessage());
            return false;
        }
    }

    private CallOption[] callOptions() {
        return new CallOption[] {authOption, CallOptions.timeout(properties.getQueryTimeout().toMillis(), TimeUnit.MILLISECONDS)};
    }

    private void ensureConnected() {
        if (flightSqlClient != null) {
            return;
        }
        synchronized (this) {
            if (flightSqlClient != null) {
                return;
            }
            if (properties.getHost() == null || properties.getHost().isBlank()) {
                throw new DremioSourceException(
                        "data-cache.dremio.host is not configured; cannot connect to Dremio", false);
            }
            try {
                Location location = properties.isSslEnabled()
                        ? Location.forGrpcTls(properties.getHost(), properties.getPort())
                        : Location.forGrpcInsecure(properties.getHost(), properties.getPort());

                FlightClient.Builder builder = FlightClient.builder(allocator, location);
                if (properties.isSslEnabled()) {
                    builder.useTls();
                    if (properties.getTrustedCertificatesPath() != null && !properties.getTrustedCertificatesPath().isBlank()) {
                        builder.trustedCertificates(new FileInputStream(properties.getTrustedCertificatesPath()));
                    }
                }
                FlightClient client = builder.build();

                // Arrow Flight's standard handshake: exchange username/password for a bearer token that is
                // then attached to every subsequent call. Falls back to per-call basic-auth headers for
                // Flight SQL servers that do not issue a session token.
                Optional<CredentialCallOption> tokenOption = client.authenticateBasicToken(
                        properties.getUsername(), properties.getPassword());
                CredentialCallOption resolvedAuthOption = tokenOption.orElseGet(
                        () -> new CredentialCallOption(
                                new BasicAuthCredentialWriter(properties.getUsername(), properties.getPassword())));

                this.flightClient = client;
                this.flightSqlClient = new FlightSqlClient(client);
                this.authOption = resolvedAuthOption;
                log.info("event=dremio-connected host={} port={} sslEnabled={}", properties.getHost(),
                        properties.getPort(), properties.isSslEnabled());
            } catch (FileNotFoundException e) {
                throw new DremioSourceException(
                        "Configured trusted-certificates-path not found: " + properties.getTrustedCertificatesPath(),
                        false, e);
            } catch (FlightRuntimeException e) {
                throw new DremioSourceException("Failed to connect/authenticate to Dremio: " + e.status().description(),
                        isRetryable(e), e);
            } catch (RuntimeException e) {
                throw new DremioSourceException("Failed to connect to Dremio at " + properties.getHost() + ":"
                        + properties.getPort(), true, e);
            }
        }
    }

    static boolean isRetryable(FlightRuntimeException e) {
        return switch (e.status().code()) {
            case UNAVAILABLE, TIMED_OUT, INTERNAL, RESOURCE_EXHAUSTED, UNKNOWN, CANCELLED -> true;
            case INVALID_ARGUMENT, UNAUTHENTICATED, UNAUTHORIZED, NOT_FOUND, ALREADY_EXISTS, UNIMPLEMENTED, OK -> false;
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (flightClient != null) {
                flightClient.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=dremio-close-interrupted", e);
        } finally {
            allocator.close();
        }
    }
}

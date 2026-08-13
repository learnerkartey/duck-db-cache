# 04. Dremio Configuration

## Connection

The production implementation (`com.enterprise.datacache.dremio.DremioFlightSqlSource`) is a real
Apache Arrow Flight SQL client:

1. Builds a `Location` via `Location.forGrpcTls(host, port)` or `forGrpcInsecure`, per
   `data-cache.dremio.ssl-enabled`.
2. Builds a `FlightClient` from a child `BufferAllocator` carved out of the shared root allocator
   (bounded by `data-cache.arrow.max-memory`).
3. Authenticates via `FlightClient.authenticateBasicToken(username, password)` (the standard Arrow
   Flight basic-auth handshake), falling back to per-call `BasicAuthCredentialWriter` headers for
   servers that don't issue a session token.
4. Wraps the client in a `FlightSqlClient` and reuses it for every subsequent query.

Credentials are never logged - `DremioProperties.toString()` deliberately omits the password.

## Configuration

```yaml
data-cache:
  dremio:
    host: ${DREMIO_HOST}
    port: ${DREMIO_PORT:32010}
    username: ${DREMIO_USERNAME}
    password: ${DREMIO_PASSWORD}
    ssl-enabled: true
    connect-timeout: 30s
    query-timeout: 30m
```

Set these via environment variables backed by an OpenShift `Secret` - never hardcode credentials
in `application.yml`. See [20-OPENSHIFT-DEPLOYMENT.md](20-OPENSHIFT-DEPLOYMENT.md).

## Query execution and streaming

For each dataset refresh:

```
FlightSqlClient.execute(sql, authOption, timeoutOption) -> FlightInfo
FlightInfo.getEndpoints() -> List<FlightEndpoint>   // Dremio commonly returns one; the client handles more
for each endpoint:
    FlightSqlClient.getStream(endpoint.getTicket(), ...) -> FlightStream
    while (flightStream.next()) { process flightStream.getRoot() }  // one VectorSchemaRoot at a time
```

This is implemented in `DremioArrowBatchStream`, which iterates every endpoint's ticket in turn so
multi-endpoint responses are handled correctly, not just assumed away.

## Multiple Flight endpoints

If Dremio returns more than one `FlightEndpoint`, each is redeemed in turn through the same
already-authenticated client (Dremio's Flight SQL endpoints resolve on the same coordinator).
Each endpoint's `FlightStream` is closed before the next is opened, so at most one is open at a
time - bounded memory throughout.

## Error classification

`DremioFlightSqlSource.isRetryable(FlightRuntimeException)` classifies gRPC status codes so the
retry policy in [07-REFRESH-AND-VERSIONING.md](07-REFRESH-AND-VERSIONING.md) never retries a
structurally broken request:

| Retryable | Not retryable |
|---|---|
| `UNAVAILABLE`, `TIMED_OUT`, `INTERNAL`, `RESOURCE_EXHAUSTED`, `UNKNOWN`, `CANCELLED` | `INVALID_ARGUMENT` (bad SQL), `UNAUTHENTICATED`, `UNAUTHORIZED`, `NOT_FOUND`, `ALREADY_EXISTS`, `UNIMPLEMENTED` |

## Health

`DremioSourceHealthIndicator` (registered only when Actuator is on the classpath) calls
`DremioSource.isHealthy()`, which runs `verification-sql` and reads one batch. This is reported as
a separate Actuator health component (`dremioSource`) from the cache's own health
(`dataCache`) - a temporarily unreachable Dremio does not mark query serving unavailable. See
[17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md).

## Live connectivity verification (not part of the normal build)

The normal `./gradlew clean build` never requires a live Dremio connection - all tests use
`InMemoryDremioSource`, a test-only synthetic implementation.

To verify a *real* Dremio connection:

```bash
export DREMIO_HOST=dremio.example.internal
export DREMIO_PORT=32010
export DREMIO_USERNAME=svc_datacache
export DREMIO_PASSWORD=********
./gradlew bootRun

curl localhost:8080/actuator/health   # "dremioSource" component reflects real connectivity
```

or trigger any configured dataset's refresh, which will genuinely execute its source SQL against
Dremio and report full timing in the response.

**This project's automated build and test suite never attempted a live Dremio connection** (no
Dremio cluster or credentials were available in this environment) - see the final verification
report for the exact statement of what was and was not exercised against a real cluster.

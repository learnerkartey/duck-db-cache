# 20. OpenShift Deployment

Example manifests live in [`openshift/`](../openshift): `deployment.yaml`, `service.yaml`,
`pvc.yaml`, `configmap.yaml`, `secret.example.yaml`. All environment-specific values (image
registry path, storage class, Dremio host, resource sizing) are placeholders - adjust them for
your cluster.

## Apply

```bash
oc apply -f openshift/pvc.yaml
oc apply -f openshift/configmap.yaml
oc create secret generic data-cache-dremio-credentials \
    --from-literal=DREMIO_USERNAME=svc_datacache \
    --from-literal=DREMIO_PASSWORD='********'
oc apply -f openshift/deployment.yaml
oc apply -f openshift/service.yaml
```

## ConfigMap vs. Secret

Everything non-sensitive (`DREMIO_HOST`, storage paths, memory/thread tuning) lives in the
`ConfigMap`. `DREMIO_USERNAME`/`DREMIO_PASSWORD` live in a `Secret`, referenced via `envFrom` in
the Deployment - never baked into the image or the ConfigMap. `application.yml` reads them via
`${DREMIO_USERNAME}`/`${DREMIO_PASSWORD}` placeholders.

## PVC

The cache **must** live on a persistent volume - `data-cache.duckdb.base-directory` should never
point at container-ephemeral storage, or every restart loses all cached data and forces a full
refresh of every dataset. `ReadWriteOnce` is sufficient given the single-writer-replica model (see
[18-OPERATIONS.md](18-OPERATIONS.md)).

## CPU/memory

See [16-MEMORY-SIZING.md](16-MEMORY-SIZING.md) for how to derive the request/limit values in
`deployment.yaml` - they are illustrative defaults, not a sizing recommendation for your workload.

## Probes

- **Liveness** (`/actuator/health/liveness`): reflects the JVM's own liveness state (Spring Boot's
  `LivenessStateHealthIndicator`), not Dremio or dataset state - a Dremio outage must never cause
  Kubernetes to restart otherwise-healthy pods.
- **Readiness** (`/actuator/health/readiness`): reflects whether the application context is fully
  started (including `DataCacheConfiguration`'s beans and startup recovery). Once ready, it
  stays ready even if a later dataset refresh fails - see the `dataCache`/`dremioSource` health
  indicator separation in [17-METRICS-AND-MONITORING.md](17-METRICS-AND-MONITORING.md).

## Graceful shutdown

`terminationGracePeriodSeconds: 90` gives in-flight refreshes and requests time to finish - see
[18-OPERATIONS.md](18-OPERATIONS.md#graceful-shutdown) for exactly what shuts down and in what
order.

## Docker image

Build with the repository's `Dockerfile`:

```bash
docker build -t data-cache-service:latest .
```

Multi-stage build (Gradle build stage + `eclipse-temurin:17-jre-jammy` runtime stage), runs as a
non-root user (`datacache`, uid 10001), and contains no credentials.

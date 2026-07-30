# Prometheus Metrics & Grafana Dashboard

Plaintext Root exposes operational metrics via Spring Boot Actuator and
[Micrometer](https://micrometer.io/). The dependency
`io.micrometer:micrometer-registry-prometheus` is on the classpath, so the
endpoint at `/actuator/prometheus` is enabled by default.

## What is exposed

| Endpoint                  | Purpose                                          | Auth                |
| ------------------------- | ------------------------------------------------ | ------------------- |
| `/actuator/health`        | Liveness/readiness probes                        | permit-all          |
| `/actuator/info`          | Application info, build version                  | `ROLE_ADMIN`        |
| `/actuator/metrics`       | Per-metric JSON view (debug/exploration)         | `ROLE_ADMIN`        |
| `/actuator/prometheus`    | Prometheus exposition format                     | `ROLE_ADMIN`        |

`/actuator/prometheus` is gated behind `ROLE_ADMIN` via
`PlaintextSecurityConfig`. In setups where Prometheus runs unauthenticated
inside the same trust boundary, scrape with an admin API token (see
`plaintext-admin-apitoken`) or expose the endpoint over a separate port
that is firewalled to the metrics network only.

## Default tags

Every metric is tagged with:

| Tag           | Source                                |
| ------------- | ------------------------------------- |
| `application` | `spring.application.name` (`plaintext-root`) |

Add per-deployment tags via `management.metrics.tags.<name>=<value>`.

## Built-in metrics

Spring Boot/Micrometer ship a sensible default set:

- `http_server_requests_seconds_count|sum|max` (per URI / status / method)
- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`
- `process_cpu_usage`, `system_load_average_1m`
- `hikaricp_connections_active|idle|usage_seconds`
- `tomcat_sessions_active_current_sessions`
- `logback_events_total` (per level)
- `flyway_migrations_executed_total`

## Prometheus scrape config

```yaml
scrape_configs:
  - job_name: plaintext-root
    metrics_path: /actuator/prometheus
    bearer_token: <api-token-with-ROLE_ADMIN>
    scheme: https
    static_configs:
      - targets:
          - plaintext-root.example.com
```

## Grafana dashboard

A starter dashboard is committed at
[`docs/dashboards/plaintext-root.json`](../dashboards/plaintext-root.json).

Import via Grafana → Dashboards → Import → upload JSON or paste the file
contents. The dashboard expects a Prometheus data source named
`Prometheus` and a label `application="plaintext-root"`. Override either
during import.

The starter board has four rows:

1. **Service health** — uptime, JVM heap, GC pauses
2. **HTTP traffic** — request rate, latency p95, error rate
3. **Database** — Hikari active/idle connections, Flyway migration count
4. **Login & security** — request counts on `/login` and `/logout`,
   failed-auth event count

The dashboard is intentionally minimal — operators are expected to extend
it with deployment-specific panels (pod/container CPU, JVM threads, etc.).

## Quick local test

```bash
mvn spring-boot:run -pl plaintext-root-webapp

# In another shell, with admin credentials:
curl -u admin:secret http://localhost:8080/actuator/prometheus | head -20
```

You should see lines like:

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{application="plaintext-root",area="heap",id="G1 Eden Space",} 1.2582912E7
…
```

---
title: Quick Start
---

# Quick Start

## Requirements

- **JDK 21+** - required by Spring Boot 4.1 + Spring AI 2.0 (Java 8/11/17 not supported)
- **Maven 3.6+**

## Run

```bash
# 1. Configure the LLM API key (env var only - never hardcode)
export DASHSCOPE_API_KEY=sk-...

# 2. Build
mvn clean package

# 3. Run
mvn spring-boot:run

# 4. Try it - feed an exception stack trace and get an LLM root cause
curl -X POST http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{"appName":"order-service","exceptionType":"NullPointerException","exceptionMessage":"Cannot invoke method on null","stackTrace":["com.foo.OrderService.process(OrderService.java:42)","com.foo.OrderController.handle(OrderController.java:17)"]}'
```

## What happens next

The exception flows through the five-layer pipeline:

1. **Collector** receives the error event via HTTP
2. **Preprocessor** retains the raw cause chain, resolves the effective cause, normalizes messages and frames, and renders strict/loose V2 fingerprints
3. **Analyzer** checks the V2 exact identity (durable history/L1), then runs L2 vector merge (if enabled), then L3 LLM root cause
4. The result lands in the cluster repository, tagged with `AnalysisPath.LLM_NEW`; enabled history also records the occurrence
5. **Aggregator** picks it up for surge detection and weekly Top-N
6. **Notifier** pushes a Feishu alert if configured

The exact V2 key is application name + fingerprint version + strict fingerprint. The loose V2
fingerprint is not an RCA or occurrence-merge key. V1 groups are read-only compatibility data;
new groups are always written as V2.

## Durable error history (optional)

The default run uses an in-memory exact-group repository and needs no database. To persist exact
groups, RCA data, and idempotent occurrence counts across restarts, enable the independent history
datasource:

```yaml
stackwatch:
  error-history:
    enabled: true
    datasource:
      url: ${ERROR_HISTORY_DATASOURCE_URL:jdbc:postgresql://localhost:5432/stackwatch}
      username: ${ERROR_HISTORY_DATASOURCE_USERNAME:stackwatch}
      password: ${ERROR_HISTORY_DATASOURCE_PASSWORD:}
```

This is independent of Incident and L2 datasource configuration. On a history lookup or write
failure, the analyzer logs/instruments the degradation and continues with the non-durable L2/L3
path; it does not claim a durable occurrence was recorded.

For deterministic V2 frame and message policy, optionally configure:

```yaml
stackwatch:
  fingerprint:
    application-packages: [com.example.orders]
    wrapper-exception-types: [com.example.OrderRequestException]
    application-error-codes: [ORDER_NOT_FOUND, PAYMENT_TIMEOUT]
```

Producers that may retry must preserve the same non-blank `eventId` in
`ErrorEvent.Context.Identity`; otherwise each submission is counted separately. `/collect` and
`/analyze` currently generate a UUID per HTTP request, so stable retry semantics require an
integration path that supplies/preserves the event identity.

## Enabling L2 (PgVector)

L2 is off by default. Enabling it requires synchronized changes in three places:

1. `pom.xml` - uncomment the PgVector starter
2. `application.yml` - remove `PgVectorStoreAutoConfiguration` from `spring.autoconfigure.exclude`
3. Set `stackwatch.l2.enabled=true` and configure `spring.datasource`

See [Architecture](/guide/architecture) for the full L1/L2/L3 cascade design.

Raw occurrence retention, restart-safe L2 state, and V1 retirement are deferred to
[GitHub issue #6](https://github.com/AllenMuu/stackwatch/issues/6).

## Enabling Deep Path

Deep Path is disabled by default and does not affect zero-infrastructure startup. To enable it,
provide PostgreSQL and set:

```bash
export INCIDENT_DATASOURCE_URL=jdbc:postgresql://localhost:5432/stackwatch
export INCIDENT_DATASOURCE_USERNAME=stackwatch
export INCIDENT_DATASOURCE_PASSWORD=change-me
mvn spring-boot:run -Dspring-boot.run.arguments=--stackwatch.incident.enabled=true
```

Flyway creates the `stackwatch_incident` schema. The runtime is asynchronous and bounded by
`stackwatch.incident.max-steps`, `max-tool-calls`, `tool-call-timeout`, and `total-timeout`.
Only read-only Toolsets are registered; Stub Adapters return deterministic fixture data and should
be replaced with deployment-configured adapters for real operations. Failed calls remain
Observations/missing evidence and are never promoted to Evidence.

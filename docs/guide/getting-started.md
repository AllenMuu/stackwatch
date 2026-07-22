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
2. **Fingerprinter** generates a SHA-256 fingerprint with framework-frame filtering
3. **Analyzer** runs the three-tier cascade: L1 cache miss -> L2 vector merge (if enabled) -> L3 LLM root cause
4. The result lands in the cluster repository, tagged with `AnalysisPath.LLM_NEW`
5. **Aggregator** picks it up for surge detection and weekly Top-N
6. **Notifier** pushes a Feishu alert if configured

## Enabling L2 (PgVector)

L2 is off by default. Enabling it requires synchronized changes in three places:

1. `pom.xml` - uncomment the PgVector starter
2. `application.yml` - remove `PgVectorStoreAutoConfiguration` from `spring.autoconfigure.exclude`
3. Set `stackwatch.l2.enabled=true` and configure `spring.datasource`

See [Architecture](/guide/architecture) for the full L1/L2/L3 cascade design.

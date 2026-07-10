
# StackWatch

> AI-driven root cause analysis for Java production errors: stacktrace fingerprint merging + LLM root cause localization + scheduled weekly digest.

StackWatch feeds production exception stack traces to an LLM for root cause localization and categorical merging, then aggregates high-frequency errors on a weekly schedule and pushes a Feishu weekly report. Goals: cut mean time to localize production incidents by ~40%, and shorten the high-frequency-issue discovery window from days to hours.

## Documentation

| Doc | Contents |
|-----|----------|
| [Detailed Design](docs/detailed-design.md) | Architecture, module design, core flows, data structures, schema, APIs, config |
| [Tech Selection](docs/tech-selection.md) | Alternatives, comparison criteria, and rationale for each tech choice |
| [Upgrade Path](docs/upgrade-path.md) | Current status, roadmap (V1.x -> V3.x), prioritization advice |

## Architecture Overview

A five-layer main pipeline plus two cross-cutting layers:

```
① Collector       -> Kafka (MVP: in-memory queue)
② Preprocessor    -> fingerprint dedup + sampling
③ Analyzer        -> L1 fingerprint / L2 vector / L3 LLM three-tier cascade (core)
④ Aggregator      -> real-time surge detection + weekly aggregation
⑤ Notifier        -> Feishu real-time alert + Feishu weekly report

Cross-cutting A: Metrics   -> localization latency / accuracy / token cost
Cross-cutting B: Feedback  -> dev feedback flows back as few-shot; gets smarter with use
```

### Three-tier merge in the Analyzer (core)

```
L1 exact fingerprint cache hit   -> 0 tokens
L2 approximate vector merge      -> 0 tokens
L3 LLM root cause on cluster rep -> actually calls the LLM (~1% of exceptions only)
```

Cost ladder: L1 ≈ 0 -> L2 ≈ 0 -> L3 is the only step that costs money.

## Requirements

- **JDK 17+** (required by Spring Boot 3.4 + Spring AI 1.0; Java 8/11 are not supported)
- Maven 3.6+

## Quick Start

```bash
# 1. Configure the LLM API key via env var (never hardcode it)
export DASHSCOPE_API_KEY=sk-...

# 2. Build
mvn clean package

# 3. Run
mvn spring-boot:run

# 4. Try it: feed an exception stack trace and get an LLM root cause
curl -X POST http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{"appName":"order-service","exceptionType":"NullPointerException","exceptionMessage":"Cannot invoke method on null","stackTrace":["com.foo.OrderService.process(OrderService.java:42)","com.foo.OrderController.handle(OrderController.java:17)"]}'
```

## Current Status (MVP)

- ✅ Domain data structures (immutable records)
- ✅ ② Preprocessor: fingerprint generation (SHA-256 + framework-frame filtering + versioning + explainable records)
- ✅ ③ Analyzer: L1 cache (Caffeine) + L3 LLM root cause (Spring AI structured output + Function Calling)
- 🚧 L2 vector merge: interface defined, pending PgVector integration
- 🚧 ① Collector: pending Kafka / Logback Appender integration
- 🚧 ④ ⑤ Aggregator / Notifier: pending Feishu weekly report implementation
- 🚧 Cross-cutting A/B: pending Micrometer + feedback loop integration

## Tech Stack

- Spring Boot 3.4 + Java 17
- Spring AI 1.0 (OpenAI-compatible protocol; switchable between DashScope/DeepSeek)
- Caffeine (L1 cache)
- PgVector (L2 vectors, pending integration)

## Acknowledgments

This project draws design inspiration from the following open-source projects:

- **PostHog** error_tracking: fingerprint algorithm versioning, embedding rendering metadata, weekly report structure
- **Arvo-AI/aurora**: post-RCA action automation, knowledge-base accumulation
- **salesforce/PyRCA**: RCA evaluation methodology

## License

MIT

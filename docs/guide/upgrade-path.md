---
title: Upgrade Path
---

# Upgrade Path

Principle: **progressive enhancement** - each step independently delivers value without breaking the running pipeline.

## Current status (v0.1.0-SNAPSHOT)

All five main layers + two cross-cutting layers are implemented. L2 / Kafka are off by default and unlock via config.

| Layer | Status |
|-------|--------|
| Domain data structures (immutable records) | Done |
| Preprocessor - fingerprint generation | Done |
| Analyzer - L1 cache + L2 vector merge + L3 LLM | Done |
| Context optimization (ContextOptimizer) | Done |
| Collector - Logback + HTTP + Kafka | Done |
| Aggregator / Notifier - surge detection + Feishu | Done |
| Cross-cutting - Micrometer metrics + feedback flywheel | Done |

## Roadmap

### V1.1 - Token metrics

Wire `ChatResponse` usage metadata into `token_cost_total` so the cost ladder has real data behind it.

### V1.2 - L2 real enablement (PgVector)

Switch `PgVectorClusterRepository.findSimilar` from in-memory cosine to real vector search once Spring AI supports `float[]` query input.

### V1.3 - Kafka real enablement

Turn on the Kafka collector for async peak-shaving in production.

### V1.4 - Redis L1 cache

Replace Caffeine with Redis for cross-instance sharing and persistence.

### V2.x - Knowledge base accumulation

Accumulate confirmed root causes into a searchable knowledge base, enabling zero-LLM resolution for known error patterns.

### V3.x - Multi-language support

Extend beyond Java to support Python and Go stack traces.

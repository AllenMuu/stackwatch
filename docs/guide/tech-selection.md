---
title: Tech Selection
---

# Tech Selection

Selection principle: **satisfy current needs + leave room for evolution** (YAGNI + extensible).

## LLM framework: Spring AI vs LangChain4j

| Dimension | Spring AI | LangChain4j |
|-----------|-----------|-------------|
| Spring ecosystem | Native | Manual bridge |
| Structured output | `.entity(Class)` one-liner | Manual parsing |
| Function Calling | `@Tool` annotation | ToolSpecification |
| Vector store | Unified `VectorStore` | Fragmented API |

**Decision: Spring AI** - native Spring Boot autoconfig, `entity(RootCauseAnalysis.class)` for structured output, `@Tool` for function calling.

## Vector store: PgVector vs Milvus vs ES

**Decision: PgVector** - cluster scale is in the tens of thousands, PgVector is sufficient. Key advantage: weekly reports need to JOIN cluster + feedback tables, PgVector does vector search + relational JOIN in one database.

## L1 cache: Caffeine vs Redis

**Decision: Caffeine (MVP) -> Redis (production)** - zero dependency, microsecond latency. Interface already abstracted (`FingerprintCache`), switching cost is low.

## Messaging: Kafka vs Redis Stream vs in-memory

**Decision: In-memory (MVP) -> Kafka (production)** - Kafka code is ready (`@ConditionalOnProperty`, off by default). Chosen over Redis Stream for stronger peak-shaving and replay.

## LLM model: DashScope vs DeepSeek vs OpenAI

**Decision: DashScope (default)** - OpenAI-compatible protocol, switchable to DeepSeek or OpenAI via config. Environment variables only: `DASHSCOPE_API_KEY`, `LLM_BASE_URL`, `LLM_CHAT_MODEL`.

## Tech stack summary

| Area | Choice |
|------|--------|
| Framework | Spring Boot 4.1 + Java 21 |
| LLM | Spring AI 2.0 (OpenAI-compatible) |
| L1 cache | Caffeine (Redis for production) |
| L2 vectors | PgVector (off by default) |
| Messaging | Kafka (off by default) |
| Resilience | Resilience4j |
| Metrics | Micrometer + Prometheus |

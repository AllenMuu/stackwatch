# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StackWatch is an AI-driven root cause analysis system for Java production errors: stacktrace fingerprint merging + LLM root cause localization + scheduled weekly digest. The core value is a "cost ladder" — the vast majority of exceptions are merged for free at L1/L2; only ~1% actually call the LLM.

## Build & Run

**JDK 21 is mandatory** (Spring Boot 4.1 + Spring AI 2.0 require it). The local JDK version is managed by **jenv** and can be freely switched — no need to manually `export JAVA_HOME`. Just ensure `jenv` is set to 21 for this project before building, or compilation will fail.

Common commands:
```bash
mvn clean package          # build
mvn spring-boot:run        # run (needs DASHSCOPE_API_KEY to actually call the LLM; otherwise only L1/L2 work)
mvn test                   # full test suite
mvn test -Dtest=ErrorAnalyzerUnitTest          # single test class
mvn test -Dtest=ErrorAnalyzerUnitTest#l1CacheHitSkipsLlmAndEmbedding  # single test method
```

**LLM secrets go through environment variables only — never hardcode them**: `DASHSCOPE_API_KEY` (required to call the LLM), `LLM_BASE_URL`, `LLM_CHAT_MODEL`, `KAFKA_BOOTSTRAP`, `FEISHU_WEBHOOK_URL`, `FEISHU_API_URL`.

## Test Layering

- **Pure unit tests (CI-friendly, no LLM key)**: `ErrorAnalyzerUnitTest`, `FingerprinterTest`. `ErrorAnalyzerUnitTest` uses Mockito to chain-mock `ChatClient` (prompt->user->tools->call->entity) and covers L1 hit / L2 merge / L3 new cluster / confidence fallback / LLM-exception fallback. **Tests for the core merge logic must not depend on a real LLM.**
- **Integration test (needs key)**: `ErrorAnalyzerTest` is guarded by `@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = "sk-.+")` and auto-skips when unconfigured.

## Architecture (what requires reading multiple files)

### Five-layer main pipeline + two cross-cutting layers

```
① Collector    -> three entry points: Logback Appender / HTTP /collect / Kafka (off by default)
② Preprocess   -> fingerprint dedup (SHA-256 + framework-frame filtering)
③ Analyzer     -> L1 fingerprint cache / L2 vector merge / L3 LLM three-tier cascade (core)
④ Aggregator   -> real-time surge detection + weekly Top-N aggregation
⑤ Notifier     -> Feishu real-time alert + Feishu weekly report
Cross-cutting A: metrics  -> Micrometer instrumentation (path latency / confidence / token cost)
Cross-cutting B: feedback -> /feedback flows few-shot back, injected into the next prompt (data flywheel)
```

Data flow: `ErrorEventCollector` (collect) -> `Fingerprinter.generate` (preprocess) -> `ErrorAnalyzer.analyze` (L1->L2->L3) -> cluster lands in `ClusterRepository` -> `WeeklyAggregator`/`HighFrequencyDetector` (aggregate) -> `FeishuClient` (deliver).

### Three-tier cascade merge (`ErrorAnalyzer.analyze` is the architectural hub)

- **L1** `FingerprintCache` (Caffeine): exact fingerprint-hash hit -> reuse historical `RootCauseAnalysis`, 0 tokens.
- **L2** `ClusterRepository.findSimilar`: vector similarity merges into an existing cluster, 0 tokens. Skipped when `embedding == null` (the signal that L2 is off).
- **L3** `callLlm`: new cluster calls ChatClient, `.entity(RootCauseAnalysis.class)` for structured output + `.tools(analysisTools)` injects `@Tool` functions to prevent hallucination; `postProcess` applies confidence fallback (confidence < threshold **or** evidence empty -> `needHumanReview=true`; LLM exception -> fallback UNKNOWN root cause).

### Zero-infrastructure startup = feature flag + autoconfigure exclusion

The project starts with **no DB / no Kafka / no vector store** by default, controlled by two coordinated mechanisms:

1. The `spring.autoconfigure.exclude` list in `application.yml` (`DataSourceAutoConfiguration` / `PgVectorStoreAutoConfiguration` / `KafkaAutoConfiguration`) — this is the master switch.
2. Each repository/channel selects its implementation via `@ConditionalOnProperty`.

**Enabling L2 (PgVector) or Kafka requires synchronized changes in three places** (missing any one causes startup or bean-injection failure); see the Javadoc on `VectorStoreConfig` and `KafkaCollectConfig`:
- pom.xml: uncomment the relevant starter / ensure the dependency is on the classpath;
- remove the corresponding AutoConfiguration from `spring.autoconfigure.exclude`;
- configure `spring.datasource` (L2) or `spring.kafka.bootstrap-servers` (Kafka);
- set `stackwatch.l2.enabled` / `stackwatch.collector.kafka.enabled` to `true`.

`ClusterRepository` has two mutually exclusive implementations selected by `stackwatch.l2.enabled`: `InMemoryClusterRepository` (`havingValue=false, matchIfMissing=true`; `findSimilar` always returns empty, forcing L3) vs `PgVectorClusterRepository` (`havingValue=true`).

> Note: even when L2 is enabled, `PgVectorClusterRepository.findSimilar` currently still uses **in-memory cosine similarity** over cluster embeddings rather than `VectorStore.similaritySearch` — because Spring AI 2.0.0's `SearchRequest.query()` accepts only `String`, not `float[]` (verified against source; see that class's Javadoc). Confirm whether your Spring AI version supports vector input before changing this.

### Three collection entry points

- `/analyze` (`AnalysisController`): calls `ErrorAnalyzer` directly; caller assembles the fields itself.
- `/collect` (`CollectController`): goes through `ErrorEventCollector`, which fills in `eventId`/`occurredAt`; the standard external reporting entry point.
- `LogbackErrorAppender`: instantiated by Logback via reflection (not a Spring bean), obtains `ErrorEventCollector` through the nested static `SpringContextHolder` (`ApplicationContextAware`); carries a `ThreadLocal` recursion guard and swallows collection exceptions to stderr to avoid self-recursion.
- Kafka (`KafkaErrorProducer`/`KafkaErrorConsumer`): off by default; async peak-shaving when enabled.

### Fingerprint algorithm (`Fingerprinter`)

Input = `exceptionType` + normalized top-N **application frames** (filters out framework prefixes like `java./javax./org.springframework./io.netty.`, because framework-internal frames jitter with dependency versions while business frames stay stable). Falls back to the top-N raw frames when fewer than 2 application frames are present. `buildEmbeddingText` feeds L2 embeddings, with the rendering strategy recorded by `EmbeddingRendering` (inspired by PostHog). Fingerprints are versioned (`FingerprintVersion`) so algorithm upgrades don't break historical merge relationships.

### Metrics instrumentation (`AnalysisMetrics`)

Unified `stackwatch.` prefix; the key tag is `path=cache_hit|vector_merged|llm_new`: `analysis_duration_seconds` (Timer; P50/P95 quantify the cache's contribution to latency), `analysis_path_total` (Counter; the 95%/4%/1% cost story), `root_cause_confidence` (DistributionSummary), `token_cost_total` (placeholder 0, pending ChatResponse usage wiring). Instrumentation is called by `ErrorAnalyzer` right before each L1/L2/L3 return.

### Weekly report scheduling (`WeeklyReportScheduler`)

`@Scheduled(cron = "0 0 9 * * MON")` — Monday 9:00 Asia/Shanghai: `WeeklyAggregator.aggregateWeekly` fetches Top-N clusters -> ChatClient summarizes -> `FeishuClient.sendWebhook`. On LLM failure it falls back to a raw-data text so the report is never missing. `@EnableScheduling` lives on `StackWatchConfig`. `ReportController` exposes a manual-trigger entry point that reuses this flow.

## Key Conventions

- **Immutability**: all `domain` records; collection fields are `List.copyOf`/`Map.copyOf`'d in the compact constructor; `ErrorCluster.embedding` (`float[]`) is cloned defensively (both on construct and access). Merging produces a new instance via `ErrorCluster.increment` — never mutated in place.
- **Configuration**: all `stackwatch.*` config uses record `@ConfigurationProperties` with range validation and default fallback inside the compact constructor (e.g. `AnalysisProperties`, `CacheProperties`). Prefixes: `stackwatch.analysis` / `stackwatch.cache` / `stackwatch.l2` / `stackwatch.collector.kafka` / `stackwatch.feishu`.
- **Prompt template**: `resources/prompts/root-cause.st` + `PromptTemplateHolder` does its own `{var}` replacement — **does not use the Spring AI template API** (version-sensitive). Adding a variable requires updating both the template and the vars map in `ErrorAnalyzer.callLlm`.
- **Error handling**: collection / delivery / embedding failures never block the main pipeline — they `log.warn` and degrade (embedding failure -> null -> skip L2; LLM failure -> fallback root cause; Feishu failure -> skip push).
- **Spring AI API is version-sensitive**: `ChatClient`'s `.tools()` / `.entity()` / `.content()` signatures may change across versions; relevant class Javadocs note "refer to official docs". Verify against the actual Spring AI 2.0.0 signatures before changing these calls.
- **Dependencies unlocked on demand**: `resilience4j`, `spring-boot-starter-data-redis`, etc. exist as comments in pom.xml; uncomment when needed (versions are managed by the Spring Boot parent, no explicit version required).

<!-- Source: superpowers-bridge/templates/adopters/CLAUDE.md.fragment.md -->
<!-- Drop this section into your project's CLAUDE.md so Claude routes future work using this schema correctly. -->
<!-- Adjust the schema name and bridge repo URL if you customized them; otherwise keep as-is. -->

## Workflow routing (read on session start)

This repo uses [`superpowers-bridge`](https://github.com/JiangWay/openspec-schemas/tree/main/superpowers-bridge) as the **default** workflow schema, integrating OpenSpec (what to do) with Superpowers (how to do it: brainstorming, writing-plans, git-worktrees, subagent-driven-development, TDD, code-review). Integration rules (language, artifact paths, PRECHECK) follow that bridge's README; this section is the routing guidance for Claude.

**Default schema = `superpowers-bridge`** (set in `openspec/config.yaml`). `/opsx:propose` therefore runs the full aggregated flow by default: `brainstorm -> proposal -> design -> specs -> tasks -> plan -> [apply] -> verify -> retrospective`. For lighter changes pass `--schema spec-driven` to `openspec new change`. Trivial fixes skip opsx entirely (direct PR).

### Entry routing

| Trigger you observe | What to do |
|---|---|
| User starts a narrative "design discussion / let's brainstorm" | Run verbal `superpowers:brainstorming`, but **do NOT** write to `docs/superpowers/specs/`. Once the conversation converges per the 5 criteria below, promote to `/opsx:propose` |
| User invokes `/opsx:propose` directly | Follow the schema's flow; artifact instructions inject at each step (default schema = superpowers-bridge) |
| User explicitly says bug fix / typo / config tweak / doc update | Direct PR — **do NOT** open a change (see skip rules below) |
| User is mid-change | Advance with `/opsx:apply` (worktree + subagent-driven-development) or `/opsx:archive`; use `/opsx:explore` to inspect, `/opsx:sync` to merge specs into main |

> v1.5.0 command set: `propose / apply / archive / explore / sync`. There is no `/opsx:new` / `/opsx:ff` / `/opsx:continue` / `/opsx:verify` in this version.

### When NOT to use opsx (direct PR)

| Scenario | Direct PR? |
|---|---|
| New feature / new capability / architectural change / breaking change | ❌ Use opsx |
| Bug fix (no contract change) / test backfill / linter tweak / non-breaking upgrade / typo / docs / config value tweak | ✅ Direct PR |

Principle: **process ceremony scales with risk**. External contracts / schema / cross-system integration / compliance → opsx. Otherwise → direct PR.

### Verbal brainstorm → opsx promotion criteria

All 5 must hold before promoting (any missing → keep brainstorming, **never** write to `docs/superpowers/specs/`):

1. **Scope locked** — one sentence describes what's in / out
2. **Major design forks resolved** — alternatives weighed; remaining TBDs have an owner and impact-scope statement
3. **Cross-system dependencies mapped** — ready / mockable / genuinely unknown — pick one per dep
4. **Acceptance criteria stateable** — concrete pass conditions (e.g., `./mvnw clean verify` passes + N deliverables)
5. **Conversation converging** — recent turns are confirmations, not new alternatives

When all 5 hold → proactively suggest "ready to `/opsx:propose`?" — wait for user ack. Never auto-trigger.

### Front-door anti-patterns (don't do)

- Letting brainstorming write to `docs/superpowers/specs/`
- Letting writing-plans write to `docs/superpowers/plans/`
- Promoting to opsx with unresolved blocking TBDs
- Opening a change for bug fix / typo

Full detail: [superpowers-bridge README §Entry & exit gates](https://github.com/JiangWay/openspec-schemas/blob/main/superpowers-bridge/README.md#entry--exit-gates).

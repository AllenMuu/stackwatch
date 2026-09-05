## Why

StackWatch's L1 fingerprint is currently an in-process Caffeine key derived from the outer
exception and implicitly parsed frames. It loses meaningful cause chains, cannot distinguish
volatile from semantic message content, is vulnerable to cross-version key ambiguity, and loses
all exact-match history after eviction or restart. This prevents deterministic grouping from being
an explainable, durable foundation for aggregation before vector similarity and LLM RCA.

## What Changes

- Introduce raw structured throwable capture, including cause chains, at every collection entry
  point while keeping legacy HTTP payloads usable during transition.
- Extract deterministic normalization and cause resolution from `Fingerprinter`; add strict and
  loose V2 fingerprints with explicit, versioned input parts and application-package policy.
- Add opt-in PostgreSQL-backed `ErrorGroup` history, atomic occurrence counters, and event-id
  deduplication. Caffeine becomes an accelerator rather than the durable source of truth when the
  feature is enabled.
- Keep the default no-database startup mode and retain V2-primary/V1-read-only compatibility.

## Capabilities

### New Capabilities

- `throwable-cause-ingestion`: Capture and validate raw throwable cause chains consistently across
  Logback, direct `Throwable`, and HTTP ingress.
- `normalized-error-fingerprints`: Produce explainable strict and loose V2 identities from an
  explicit normalized error model.
- `durable-error-groups`: Persist exact-match identity, RCA reuse data, and idempotent group-level
  occurrence updates behind an opt-in history feature.

### Modified Capabilities

- None.

## Impact

Affected areas include the `ErrorEvent` domain/API DTOs, collector paths, preprocessing package,
fingerprint cache and `ErrorAnalyzer` routing, configuration, Flyway migrations, and unit and
PostgreSQL integration tests. The change introduces an opt-in PostgreSQL dependency at runtime but
does not replace the existing L2/L3 analysis behavior. Retained occurrence detail, restart-safe L2,
and the V1 retirement/backfill execution are explicitly deferred to GitHub issue #6.

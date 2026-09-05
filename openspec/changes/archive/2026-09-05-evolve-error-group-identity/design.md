## Context

The Fast Path currently constructs `ErrorEvent` from only the outer exception and lets
`Fingerprinter` parse stack lines, classify application code, select frames, and hash them. L1 is a
Caffeine cache keyed by a bare hash and returns an RCA without recording a new occurrence. This is
fast but not durable or explainable. The system must keep its existing default ability to start
without databases, Kafka, or vectors, and collection/delivery failures must continue to degrade
without blocking the primary RCA result.

## Goals / Non-Goals

**Goals:**

- Make deterministic error identity explicit, reproducible, versioned, and scoped to an
  application.
- Preserve meaningful primary cause chains and normalize only in preprocessing.
- Provide durable, idempotent exact-match RCA reuse and aggregate counters when history is enabled.
- Preserve legacy HTTP ingestion and zero-infrastructure startup.

**Non-Goals:**

- Persist full raw occurrence records, trace drill-down data, or retention policies.
- Make L2 vector similarity durable after restart or replace it with a different vector API.
- Replace the LLM RCA pipeline or auto-reuse an RCA merely because a loose fingerprint matches.
- Execute a V1 backfill or retirement; #6 owns that operational work.

## Decisions

### D1: Separate raw facts, normalization, and identity

- **Choice:** Introduce immutable `ThrowableInfo` as the raw exception tree and evolve
  `ErrorEvent` to contain it. Add `CauseResolver`, `MessageNormalizer`, `StackFrameNormalizer`,
  `ErrorNormalizer`, and `NormalizedError`; `Fingerprinter` only renders canonical V1/V2 inputs.
- **Reason:** Collection remains lossless and policy-free, while identity is independently
  deterministic and testable.
- **Alternatives considered:** Retaining outer fields and extending `Fingerprinter` was rejected
  because it keeps normalization policy implicit and cannot represent a cause chain.

`ThrowableInfo` stores type, raw message, raw stack-frame strings, and its direct cause. Direct
`Throwable` and Logback `IThrowableProxy` traversals use identity/cycle protection and a maximum
depth of 32. HTTP receives either `exception` or legacy outer fields; both forms are rejected when
present together, and legacy fields are converted to a one-node tree at the boundary.

### D2: Deterministic effective-cause and frame policy

- **Choice:** Traverse the primary cause chain, skip the configured/known wrapper types, and select
  the deepest non-wrapper cause with a type. Select frames in this order: effective-cause
  application frames, outer application frames, effective-cause raw frames, outer raw frames.
- **Reason:** It makes wrapper-equivalent errors stable while avoiding arbitrary outer framework
  frames when the root cause holds useful evidence.
- **Alternatives considered:** Always choosing the deepest cause was rejected because wrappers and
  empty/infrastructure stacks can hide business context. Suppressed exceptions are not used in V2
  because their semantics are not consistently causal.

`application-packages` is a configurable allowlist. When nonempty, only matching classes are
application frames. The existing framework-prefix denylist is a fallback only when no allowlist is
configured.

### D3: Versioned strict and loose V2 identities

- **Choice:** V2 strict canonical input includes application name, effective type, normalized
  message template, and selected normalized frames. V2 loose input omits the message. Both retain
  canonical record parts; storage and cache keys are `(appName, version, strictHash)`.
- **Reason:** Strict matching avoids over-merging semantic variants, while loose matching exposes
  safe candidate grouping without treating an approximate signal as ground truth.
- **Alternatives considered:** A bare hash key and automatic loose-hit RCA reuse were rejected for
  cross-app/version ambiguity and silent misattribution risk.

`MessageNormalizer` uses documented ordered rules for UUIDs, IPs, time/date values, long hashes,
long business IDs, selected URL-query values, and whitespace. It must preserve HTTP status,
SQLState, errno, gRPC status, and configured stable application codes. Fixtures pin these rules to
the V2 version.

### D4: Opt-in durable history with an explicit degradation boundary

- **Choice:** Add `stackwatch.error-history.enabled` and independent error-history datasource
  configuration. When enabled, a PostgreSQL `ErrorGroupRepository` and its Flyway migration are
  created; when disabled, an in-memory/no-history implementation preserves the existing startup
  path. Caffeine caches an `ErrorGroup` lookup target rather than only an RCA.
- **Reason:** PostgreSQL is required for durable identity but must not be an unconditional Fast Path
  dependency or silently coupled to Incident/L2 activation.
- **Alternatives considered:** Making PostgreSQL mandatory breaks zero-infrastructure startup;
  keeping Caffeine authoritative fails restart and eviction requirements; coupling history to the
  Incident or L2 datasource creates configuration-order and lifecycle coupling.

The durable schema contains `error_groups` with a unique `(app_name, fingerprint_version,
strict_fingerprint)` key and fields for loose fingerprint, outer/effective type, message template,
canonical frame data, RCA JSON, linked cluster ID, first/last seen, count, and timestamps. A small
`accepted_error_events` table has a unique `(app_name, event_id)` key and stores only the group
reference and accepted time; it is an idempotency ledger, not the deferred full `ErrorOccurrence`
model.

Repository mutation APIs accept command records (rather than long argument lists). One database
transaction inserts the event key if new and then creates/updates the group with
`first_seen = LEAST(...)`, `last_seen = GREATEST(...)`, and exactly one count increment. A duplicate
event returns the existing group without another increment.

### D5: Analysis routing and compatibility

- **Choice:** `ErrorAnalyzer` normalizes once, computes V2 identity, checks Caffeine and then the
  durable repository for strict matches, and records every accepted exact occurrence before
  returning the stored RCA. On an L2 or L3 result, it creates a V2 group linked to the resolved
  cluster/RCA and records the first event before warming Caffeine. Loose matches may be exposed as
  a candidate to later logic but do not affect the return path in this change.
- **Reason:** The durable group remains authoritative while preserving the current L2/L3 fallback.
- **Alternatives considered:** Returning directly from Caffeine was rejected because it skips
  occurrence mutation; persisting only after the response risks losing the event and counter.

New writes are V2. Lookup is V2-primary followed by a V1 read-only lookup when legacy data exists.
V1 hits are never silently rewritten, merged, or treated as V2. A history write outage logs and
metrics the degraded mode, then preserves the current non-durable L2/L3 behavior rather than
breaking collection; it must not falsely claim a durable occurrence update succeeded.

## Risks / Trade-offs

- [Risk] Message patterns can hide a stable semantic discriminator → Mitigation: ordered regression
  fixtures retain known status/code forms, and normalization changes require a new fingerprint
  version.
- [Risk] Database outages reduce durable counting → Mitigation: best-effort Fast Path degradation,
  explicit log/metric, and no false persistence acknowledgement.
- [Risk] Event IDs absent from legacy/logback sources cannot provide retry idempotency → Mitigation:
  collector-generated IDs retain at-least-once behavior there; document that external producers must
  supply stable IDs for exactly-once group counting.
- [Trade-off] An idempotency ledger is a minimal occurrence store before the P1 model → Accepted
  because exact counters cannot be correct across retried submissions without it.
- [Trade-off] Separate history datasource configuration duplicates deployment settings → Accepted
  to keep history lifecycle independent from Incident and L2. Deployments may point both at the same
  PostgreSQL server intentionally.

## Migration Plan

1. Deploy with `stackwatch.error-history.enabled=false`; all existing Fast Path behavior remains
   available and no error-history datasource or migration is created.
2. Provision the history PostgreSQL datasource, enable the flag, and run the history migration.
3. New events write V2 groups; operational checks confirm durable lookup after cache eviction and
   process restart.
4. If history writes fail, disable the flag to return to the existing in-memory-only behavior. No
   destructive migration rollback is required because the new tables are additive.
5. Keep V1 lookup read-only. Child #6 defines backfill, adoption metrics, and removal criteria.

## Open Questions

None for P0. Deployment owners must provide actual application-package prefixes and stable external
event IDs where retry-safe counting is required; these are configuration/input obligations, not
design forks.

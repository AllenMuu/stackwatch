# Brainstorm capture — evolve error-group identity

Classification: architectural. This changes the ingestion contract, deterministic identity model,
persistence boundary, and analysis routing while preserving the existing L2/L3 RCA pipeline.

## Background

Issue #3 identified that the existing L1 cache is only an in-process optimization: it fingerprints
the outer exception from implicitly normalized stack frames, loses cause chains, uses a bare hash
key, and discards identity history on cache eviction or restart. The result is an unstable
aggregation identity that cannot safely support accurate occurrence counting.

The project must retain zero-infrastructure startup by default. Therefore durable history cannot
be silently introduced as an unconditional dependency.

## Options considered

1. Keep Caffeine as the authoritative L1 store and improve only the hash.
   This preserves startup simplicity but fails the durable-history and restart acceptance criteria.

2. Make PostgreSQL mandatory for all installations.
   This achieves durability but breaks the established zero-infrastructure operating mode.

3. Add an opt-in durable error-history module, with Caffeine as an accelerator.
   This satisfies durable deployments while preserving the default mode. Chosen.

For loose fingerprints, automatic RCA reuse was considered. It is rejected because the message
may contain stable semantic differences, such as HTTP status or SQLState. Loose matching remains
a candidate signal only.

## Confirmed decisions

1. This change implements only Issue #3 P0: full cause-chain capture, explicit normalization,
   strict/loose V2 fingerprints, durable `ErrorGroup` history, and accurate L1 occurrence updates.
   Deferred P1/P2 work is GitHub child issue #6.
2. `stackwatch.error-history.enabled` gates PostgreSQL/Flyway-backed `ErrorGroup` history. When
   disabled, the current zero-infrastructure in-memory behavior remains available and is explicitly
   non-durable. Caffeine is never the source of truth when history is enabled.
3. All ingress paths normalize to a raw `ThrowableInfo` tree. HTTP accepts either a new optional
   nested `exception` object or the legacy outer exception fields, but rejects payloads containing
   both forms.
4. `CauseResolver` follows the primary cause chain only, guards object-identity cycles, limits
   traversal to 32 levels, skips declared wrapper types, and selects the deepest non-wrapper cause
   with a type. Suppressed exceptions are retained as raw facts but do not affect V2.
5. Frame selection is deterministic: effective-cause application frames, outer application frames,
   effective-cause raw frames, then outer raw frames. An explicit package allowlist takes priority;
   the existing denylist applies only without configured packages.
6. V2 strict identity contains app name, effective exception type, normalized message template, and
   top normalized application frames. V2 loose identity omits the message and never auto-reuses RCA
   or combines occurrence counts.
7. Every lookup key is `(appName, fingerprintVersion, strictFingerprint)`, including Caffeine.
   P0 accepts an optional external event ID and persists a minimal deduplication registration so
   retry submissions do not inflate counters. Group updates are atomic: first seen is the minimum,
   last seen is the maximum, and count increments once per accepted event.
8. Message normalization is deterministic, versioned, and covered by regression fixtures. It masks
   volatile UUID, IP, time, long-ID, and selected URL-query values while retaining stable HTTP,
   SQLState, errno, gRPC, and application error codes.
9. New durable groups use V2. Lookup is V2-primary then V1 read-only. V1 hits are not silently
   migrated or merged. The observable retirement/backfill procedure belongs to #6.

## Success criteria

- Wrapper-equivalent failures map to the same effective type and strict identity when their
  normalized facts match; meaningful root-cause differences do not.
- Line-number and framework-stack churn do not change V2 when selected business frames are stable.
- Cache expiration and process restart do not lose an enabled durable group's RCA or occurrence
  history.
- Duplicate external event IDs do not increment a group twice; distinct events preserve accurate
  first/last seen values even when received out of order.
- L2/L3 remain reachable when strict identity does not match.

## Scope boundary

This change does not introduce retained `ErrorOccurrence` drill-down records, restart-safe L2
vector search, or the V1 retirement/backfill execution. Those are tracked by child Issue #6.

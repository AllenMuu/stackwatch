## 1. Raw throwable ingress contract

- [x] 1.1 Add immutable `ThrowableInfo` and evolve `ErrorEvent` plus HTTP DTOs to support either
  structured throwable input or legacy outer fields, with validation that rejects mixed forms.
- [x] 1.2 Add cycle-safe, depth-limited primary-cause extraction for direct `Throwable` and Logback
  `IThrowableProxy` collection paths.
- [x] 1.3 Add unit/controller tests for legacy HTTP compatibility, structured causes, mixed-form
  rejection, wrapper chains, cycles, and depth limits.

## 2. Explicit normalization and V2 fingerprinting

- [x] 2.1 Add `CauseResolver`, `StackFrameNormalizer`, `MessageNormalizer`, `ErrorNormalizer`, and
  immutable normalized domain records; move classification and frame-selection policy out of
  `Fingerprinter`.
- [x] 2.2 Add fingerprint configuration for application-package allowlists and deterministic,
  versioned message-normalization rules while retaining semantic status/code tokens.
- [x] 2.3 Extend `ErrorFingerprint` to expose strict and loose V2 identities with explainable
  canonical record parts; retain V1 compatibility lookup data.
- [x] 2.4 Add pure unit regression fixtures for wrapper resolution, line-number churn, allowlist
  precedence, volatile token masking, semantic code preservation, strict/loose differences, and
  cross-application identity separation.

## 3. Opt-in durable ErrorGroup history

- [x] 3.1 Add `ErrorHistoryProperties`, independently gated datasource/Flyway configuration, and an
  in-memory/no-history repository implementation that preserves default zero-infrastructure startup.
- [x] 3.2 Define immutable `ErrorGroup`, typed exact-key, and occurrence-recording command/result
  models without public methods exceeding five parameters.
- [x] 3.3 Add additive PostgreSQL migrations for `error_groups` and minimal idempotency ledger tables
  with composite uniqueness and indexes for exact lookup.
- [x] 3.4 Implement transactional PostgreSQL exact lookup/create/record-occurrence behavior with
  first/last-seen min/max semantics and duplicate event-ID suppression.
- [x] 3.5 Add PostgreSQL integration tests covering migration, restart/cache-eviction lookup,
  cross-app/version isolation, duplicate events, and out-of-order event times.

## 4. Analyzer and cache routing

- [x] 4.1 Refactor `FingerprintCache` to cache typed error-group lookup targets rather than bare RCA
  values, ensuring a cache hit cannot bypass durable occurrence recording.
- [x] 4.2 Update `ErrorAnalyzer` to normalize once; perform V2 strict cache/repository lookup;
  record accepted occurrences; create V2 groups for L2/L3 outcomes; and preserve non-authoritative
  loose-match behavior.
- [x] 4.3 Implement V2-primary then V1 read-only lookup and explicit degradation logging/metrics for
  history persistence failure without interrupting L2/L3 fallback.
- [x] 4.4 Expand analyzer unit tests for durable exact-hit mutation, V1 fallback, history-disabled
  behavior, cache warming, database failure degradation, and loose-match non-reuse.

## 5. Operational verification and documentation

- [x] 5.1 Document error-history enablement, independent datasource setup, application-package
  configuration, stable external event-ID requirements, and the deferred #6 scope.
- [x] 5.2 Run focused unit tests and PostgreSQL integration tests, then run the full Maven suite;
  capture restart and default-startup verification evidence.

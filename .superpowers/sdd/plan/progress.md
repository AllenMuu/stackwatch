# SDD ledger — plan: openspec/changes/evolve-error-group-identity/plan.md

## Pre-flight interface scan

| Tasks | Shared file or interface | Finding |
| --- | --- | --- |
| 1 / 2 | `ErrorEvent.exception()` and `ThrowableInfo` | Task 1 produces the raw tree; Task 2 consumes it through `ErrorNormalizer`. No conflict. |
| 2 / 3 | `FingerprintVersion`, V2 strict identity | Task 2 produces V2 hashes; Task 3 stores typed keys. No conflict. |
| 3 / 4 | `ErrorGroupRepository`, `RecordOccurrenceCommand`, error-history migration | Task 3 defines contract/schema; Task 4 provides PostgreSQL implementation. No conflict. |
| 3 / 5 | typed `ErrorGroup` cache target | Task 3 defines the target; Task 5 changes the cache and analyzer to consume it. No conflict. |
| 4 / 5 | transactional occurrence result | Task 4 returns accepted/persisted group; Task 5 routes exact hits through it. No conflict. |
| 5 / 6 | feature-flag behavior and verification claims | Task 6 documents only behavior proven by Task 5 tests. No conflict. |
| 1 | Contract test versus implementation | Structured and legacy forms are mutually exclusive; controller validation is required. Consistent. |
| 2 | Fixtures versus normalization implementation | Required token preservation and ordered masking are specified; new V2 API remains compatible with V1. Consistent. |
| 3 | Default-startup requirement versus datasource creation | Conditional configuration avoids unconditional datasource creation. Consistent. |
| 4 | Idempotency versus counter update | Event-ledger insert controls the single increment in one transaction. Consistent. |
| 5 | Caffeine fast path versus durable occurrence mutation | Cache stores a group target, not bare RCA; mutation is not skipped. Consistent. |
| 6 | Documentation versus unavailable Docker | Documentation and unit tests can proceed; Docker-dependent integration verification is explicitly environment-gated. Consistent. |

Task 1: review failed — P2 public callable parameter-count violations; P2 missing JSON deserialization and HTTP 400 contract coverage.
Task 1: fix round 1/5 (2 addressed, 1 open — source-compatible long constructors removed; commits b1c9cb4..dcff5c5)
Task 1: Ruling: retain the composed constructors/factories with fewer than five parameters and do not restore legacy six-to-eight-argument Java constructors — the binding project style rule and the OpenSpec HTTP compatibility requirement take precedence; internal callers and JSON payloads remain compatible — cost if wrong: an untracked external Java consumer must migrate to the composed factory API.
Task 1: complete (commits c5e8ae9..dcff5c5, 1 parked)
Task 2: review failed — High missing identity-based cycle detection in CauseResolver; Medium max-depth off-by-one; Medium no enforced normalize-before-V2 boundary; Minor/quality mismatch between loose hash input and retained message record.
Task 2: fix round 1/5 (4 addressed, 0 open — commits f200f9f..abce250e)
Task 2: minor (deferred): implementer could not reproduce the RED baseline because the worktree already had partial Task 2 files; focused and full GREEN suites passed.
Task 2: complete (commits dcff5c5..abce250e, 1 deferred minor)
Task 3: review failed — P1 model parameter-limit violation; P1 nullable loose fingerprint; P1 cluster_id UUID/String mismatch; P1 unrelated tracked report overwrite; P2 datasource property-shape mismatch; P1 simultaneous Incident/history wiring unverified; P2 narrow model/schema coverage.
Task 3: fix round 1/5 (7 addressed, 0 open — commits 31009d4..c9963ea)
Task 3: re-review PASS with one P2 test-quality finding; follow-up removed tautological cross-app/version assertion and added independent IDs/counts/unknown-key checks (baf506b).
Task 3: complete (commits 611b78d..baf506b, PostgreSQL dual-datasource migration remains Docker-gated/unverified)
Task 4: review failed — P1 dual-datasource JdbcTemplate/transaction-manager binding could redirect Incident to History; P2 cross-identity duplicate event could create an empty group; P2 blank eventId semantics diverged.
Task 4: fix round 1/5 (all addressed — commits 7ff4d60..5d46a02; test transaction-manager alias in 631a13d)
Task 4: re-review PASS; compile and non-Docker focused tests pass, PostgreSQL/Incident Testcontainers suites skipped because Docker is unavailable.
Task 4: complete (commits 7890668..5d46a02, plus 631a13d test binding)
Task 5: review failed — P2 exact-hit persistence failure could continue into V1 reuse; P2 new-group duplicate record result ignored, allowing non-authoritative RCA return.
Task 5: fix round 1/5 (both addressed — c2ce70a; 21 analyzer tests pass)
Task 5: re-review PASS; exact-hit failure disables durable compatibility lookup and duplicate persistence uses authoritative stored group/RCA.
Task 5: complete (commits c9397e0..c2ce70a)
Task 6: review failed — P2 docs described V1 as fully read-only although V1 hits still record occurrence counts.
Task 6: fix round 1/5 (clarified migration/re-keying read-only semantics in README and guides — 743f447)
Task 6: re-review PASS; configuration examples, V2/V1 behavior, degradation, stable eventId, and #6 boundary are accurate.
Task 6: complete (commits a5b69af..743f447; full suite 141 passed, 22 skipped without Docker/LLM)

# Task 3 implementation report — Skills, Toolsets, and evidence governance

## Status

Completed Task 3.1–3.3 in the `add-deep-incident-investigation` worktree. The OpenSpec task
checklist was deliberately not changed.

## Delivered

- Added the three versioned JVM Skill resources:
  - `incident-skills/spring/feign-timeout/SKILL.md`
  - `incident-skills/java/null-pointer/SKILL.md`
  - `incident-skills/redis/connection-pool-exhaustion/SKILL.md`
- Added `IncidentSkillLoader`, immutable `IncidentSkill`/`IncidentSignals` contracts, and a
  deterministic `SkillMatcher`.
  - The matcher searches exception signals plus the existing Fast Path `RootCauseAnalysis` fields.
  - Selection is stable by Skill ID and has no LLM dependency or selection call.
- Added typed, fixed-scope Toolset contracts:
  - `Toolset`, `ToolScope`, `ToolRequest`, `ToolRawResult`, normalized `ToolResult`, and status.
  - `ToolRegistry` supplies a configuration seam for explicitly registered read-only adapters.
  - `ToolExecutor` accepts only the three configured Toolset names and the fixed incident identity
    scope; non-empty caller/LLM operational input is rejected before adapter invocation.
- Added deterministic local-first adapters for Logs, Trace, and Git/Deployment.
  - They provide fixed Feign-timeout fixture data and fixed observation windows without a provider
    dependency.
- Added result normalization and evidence governance.
  - Adapter summaries/provenance are redacted for credentials and URLs and assigned SHA-256 hashes.
  - Results convert only to `Observation` values. Adapter errors and policy rejections produce
    `FAILURE`/`REJECTED` Observations plus a missing-evidence entry; they never manufacture
    `Evidence`.
- Added focused unit coverage for resource loading, deterministic Feign matching, configured-name
  enforcement, URL/credential/shell/SQL/Kubernetes rejection before adapter invocation, redaction,
  deterministic stub output, and failure-to-Observation handling.

## TDD evidence

- RED: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` failed because the
  `com.stackwatch.incident.skills` and `com.stackwatch.incident.toolset` production packages did
  not yet exist.
- GREEN: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` passed: 8 tests, 0
  failures/errors.

## Verification

- `git diff --check` passed.
- Focused suite: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` — 8 passed.
- Full suite: `jenv exec mvn -o test` — 64 tests, 0 failures, 0 errors, 11 skipped.

## Environment notes

- The full suite requires host process access on this machine because the sandbox blocks Mockito's
  dynamic Java-agent attachment. The same host-access command passed.
- PostgreSQL Testcontainers tests are skipped because Docker is unavailable; the LLM-key integration
  test is also correctly skipped when its key is unset.

## Review round 1 safety fixes

- `ToolRequest` now accepts only a Toolset name. `ToolExecutor.execute(Incident, ToolRequest)`
  derives `ToolScope` exclusively from the server-owned Incident identity before calling an adapter;
  no caller/LLM request can provide a scope, query selector, endpoint, or credentials.
- Reworked result normalization around a single internal `ResultDetails` value so every normalizer
  method has fewer than five parameters. The resulting content hash is calculated from redacted
  Observation-compatible fields only.
- Expanded secret redaction to JSON key/value fields and quoted field assignments for authorization,
  tokens, passwords, secrets, and API keys. The same normalizer processes adapter exception messages
  before they are converted to Observations or missing-evidence text.
- Added regressions for server-owned scope construction, path-shaped and other hostile Toolset names,
  JSON and quoted secrets from adapter exceptions, and Feign matching where Fast Path RCA supplies a
  required signal absent from exception data.

### Review-round TDD and verification

- RED: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` failed to compile because
  the old request/executor API still exposed `ToolScope` and did not accept an `Incident`.
- GREEN: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` — 11 passed.
- Full verification: `jenv exec mvn -o test` with host process access — 67 tests, 0 failures, 0
  errors, 11 Docker/LLM-gated skips.

## Review round 2 redaction fix

- Tightened quoted-secret matching so the captured opening quote must also terminate the value. An
  apostrophe inside a double-quoted value (and the converse) can no longer end redaction early and
  leave a suffix in an Observation or its missing-evidence text.
- Added a failure-path regression that converts `password="abc'def"` and `password='abc"def'`
  through `ToolResult` to `Observation`, asserting that only fully redacted values are retained.

### Review-round TDD and verification

- RED: `jenv exec mvn -o test -Dtest=ToolExecutorTest` failed because the old matcher terminated
  each password on the other quote character, retaining `def` in the normalized result.
- GREEN: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` — 12 passed.
- Full verification: `jenv exec mvn -o test` with host process access — 68 tests, 0 failures, 0
  errors, 11 Docker/LLM-gated skips.

## Review round 3 redaction fix

- Replaced quoted-secret value matching with a deterministic scanner. It advances through escaped
  characters and newlines until it finds the same opening quote; when no closing quote exists, it
  conservatively redacts through end of input so no suffix is retained.
- Added adapter failure-path regressions for `password="abc'def\nxyz"` and unterminated
  `password="secret`, covering the normalized `ToolResult`, derived `Observation`, and
  `missingEvidence` text.

### Review-round TDD and verification

- RED: `jenv exec mvn -o test -Dtest=ToolExecutorTest` — 2 failures reproduced the newline and
  unterminated quoted-password leaks.
- GREEN: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest` — 14 passed.
- Full verification: `jenv exec mvn -o test` with host process access — 70 tests, 0 failures, 0
  errors, 11 Docker/LLM-gated skips.

## Review round 4 redaction and Evidence fixes

- Hardened quoted-secret scanning with key-boundary segmentation. If a second credential assignment
  starts before the first value can be closed, normalization now redacts conservatively through end
  of input. Doubled matching quotes are treated as a value escape, preventing a suffix leak.
- Added an escaped-JSON scanner for stringified secret keys in payloads such as
  `{"password":"value"}`. It fail-closes from the escaped value delimiter, so raw
  stringified payload suffixes never reach a `ToolResult`, derived `Observation`, or
  `missingEvidence`.
- Added the required Task 2 invariant: `Evidence` rejects `FAILURE` and `REJECTED` Observations;
  `PostgresIncidentRepository.appendEvidence` loads the referenced same-Incident Observation and
  enforces that eligibility before an Evidence row can be inserted. The Docker-gated repository
  regression covers both prohibited statuses.
- No runtime persistence was added here. Task 4 owns persisting ToolResult-derived Observations and
  missing-evidence entries during investigation execution.

### Review-round TDD and verification

- RED: `jenv exec mvn -o test -Dtest=ToolExecutorTest` — 3 failures reproduced chained,
  doubled-quote, and escaped-JSON secret leaks. The new Evidence contract test then failed to
  compile because `Evidence.requireEligibleObservation` did not exist.
- GREEN: `jenv exec mvn -o test -Dtest=SkillMatcherTest,ToolExecutorTest,IncidentDomainTest,PostgresIncidentRepositoryTest`
  — 36 tests, 0 failures/errors, 10 Docker-gated skips.
- Full verification: `jenv exec mvn -o test` with host process access — 75 tests, 0 failures, 0
  errors, 12 Docker/LLM-gated skips.

## Review round 5 fail-closed hardening

- Reworked assignment redaction into a single boundary-aware scanner that preserves the original
  key/delimiter syntax (including escaped JSON quotes), handles repeated backslash escaping,
  doubled quotes, embedded newlines, and chained malformed credentials, and replaces each value
  before URL normalization. This closes nested stringified JSON bypasses without leaking suffixes.
- Changed evidence eligibility to an explicit `SUCCESS` allowlist after trimming and
  case-normalization. `TIMEOUT`, `ERROR`, malformed statuses, and all other non-success
  observations are rejected before persistence.
- Added regressions for twice-escaped nested JSON credentials and non-success evidence statuses.

### Review-round TDD and verification

- RED: `jenv exec mvn -o -Dtest=ToolExecutorTest test` exposed six quote/escape regressions while
  validating the nested-redaction fix.
- GREEN: `jenv exec mvn -o -Dtest=ToolExecutorTest test` — 15 passed.

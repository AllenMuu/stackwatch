# SDD ledger — plan: openspec/changes/add-deep-incident-investigation/plan.md

## Pre-flight scan

| Tasks / interface | Finding | Ruling |
|---|---|---|
| Task 1 → Task 2 | Task 1 provides Flyway schema and configuration; Task 2 consumes it for repository persistence. | Clean; schema foundation precedes repositories. |
| Task 2 → Task 3 | Domain records provide Evidence/Observation and repository persistence; Toolsets convert results into those types. | Clean; keep domain independent of Toolset adapters. |
| Task 3 → Task 4 | Runtime consumes Skill matcher and typed Tool Executor. | Clean; use interfaces rather than concrete Stub Adapters. |
| Task 4 → Task 5 | APIs/evaluator consume runtime and report queries. | Clean; runtime must expose service-level entry points. |
| Task 1 / runtime-platform spec | Existing default excludes datasource; Incident must enable only when feature flag is true. | Ruling: use conditional datasource/autoconfiguration configuration so disabled startup remains unchanged. |

## Environment

- Baseline `jenv exec mvn test`: 16 Mockito errors caused by Byte Buddy self-attach being blocked on this macOS host, including existing ErrorAnalyzerUnitTest and FeedbackControllerUnitTest. Not caused by this change.
- `mvn` without `jenv exec` uses JDK 26; all Maven commands for this plan use `jenv exec mvn`.

## Task 1 review — round 1

- P1: Flyway is enabled whenever another datasource exists, so L2 could run Incident migrations when Incident is disabled.
- Ruling: Bind Flyway activation to `stackwatch.incident.enabled` and add a test proving Incident migrations do not activate merely because another datasource is present. This preserves the independent opt-in requirement in the binding spec.

## Task 1 complete

- Commits: `fb63d35`, `b6c0b84`.
- Review: spec compliance PASS; task quality PASS after fix round 1.

## Task 2 review — round 1

- High: Report/hypothesis construction and save must enforce the zero/one/two independent-source verification gate; empty evidence requires UNKNOWN and human review.
- High: Persisted lifecycle needs compare-and-set status transitions so a reused or stale aggregate cannot start a second worker or resurrect terminal state.
- High: Evidence insertion must validate that its Observation belongs to the same Incident.
- Medium: Preserve cited evidence and hypothesis audit links in persistence; update the schema if a link table is required.
- Medium: Trigger/step persistence must advance incident `updated_at` used for stale-run detection; integration tests must exercise transactional behavior through the Spring proxy and include ownership/concurrency cases.
- Ruling: Expand Task 2's migration/repository tests as necessary to enforce these invariants. This is required by the accepted evidence-governed and bounded-runtime specs, not scope expansion.

## Task 2 review — round 2

- High: The repository must derive source type and verification status from persisted Evidence rows by ID, not caller-supplied copies.
- Medium: A report must persist its own cited-evidence set; later Evidence appends MUST NOT change the historical report view.
- Low: Add a concurrent `startIfPending` integration assertion where practical.
- Ruling: Add an explicit report-to-evidence link table/migration if needed and treat database facts as authoritative at every report write boundary.

## Task 2 complete

- Commits: `f710f46`, `687b2d5`, `25aad1a`, `b2996a5`.
- Review: spec compliance PASS; task quality PASS after three fix rounds.

## Task 3 review — round 1

- P1: Tool scopes must be server-owned from Incident context/configuration, not arbitrary ToolRequest fields.
- P1: Redaction must cover JSON/quoted token and authorization fields before Observation persistence, including adapter error messages.
- P2: Add hostile-scope, path-shaped-name, JSON redaction, and Fast Path RCA matching tests.
- P3: Refactor normalizer methods to fewer than five parameters.
- Ruling: Preserve typed Toolset interfaces while moving scope construction inside the executor/runtime boundary; no untrusted caller controls a query selector.

## Task 3 review — rounds 2–5

- Round 2 found mixed-quote suffix leakage; scanner fix and regression tests added.
- Round 3 found newline/unterminated quote leakage; fail-closed scanner fix and regression tests added.
- Round 4 found chained/doubled-quote and escaped-JSON leakage plus Evidence status bypass; fixes and
  ownership/evidence tests added.
- Round 5 found nested twice-escaped JSON leakage and fail-open non-success statuses; the assignment
  scanner was simplified to preserve syntax while redacting all value spans, and Evidence now uses a
  trimmed case-insensitive `SUCCESS` allowlist.
- Focused verification: `jenv exec mvn -o -Dtest=ToolExecutorTest test` — 15 passed.

## Task 3 complete

- Commits: `ae72d57`, `2dbe49e`, `fbc91ca`, `24305d4`, `38b63b0`, plus the round-5 hardening commit.
- Review: spec compliance PASS after five fix rounds; task quality PASS.

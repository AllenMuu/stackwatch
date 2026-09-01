## Why

StackWatch can cheaply classify repeated Java exceptions, but a new or poorly evidenced cluster still leaves operators with a stack trace and an unverified LLM answer. Adding an evidence-grounded, JVM-focused Deep Investigation path lets the system collect and audit facts without sacrificing the Fast Path's latency, cost, or safety properties.

## What Changes

**Incident escalation and investigation**
- From: New L3 clusters and low-confidence RCA end after the Fast Path result.
- To: Qualifying clusters can create or reuse an asynchronously investigated, PostgreSQL-backed Incident.
- Reason: Multi-source evidence and an auditable report require a bounded investigation lifecycle.
- Impact: Non-breaking; the default Fast Path contract remains unchanged.

**Evidence-grounded reporting**
- Add structured observations, evidence, hypotheses, reports, and auditable investigation steps, separate from the existing `RootCauseAnalysis`.

**Read-only procedural investigation**
- Add deterministic JVM Skills and a typed Toolset runtime with Logs, Trace, and Git/Deployment Stub Adapters; no caller or LLM can supply arbitrary operational commands or credentials.

**Operations and verification**
- Add incident APIs, independent feature configuration, PostgreSQL/Flyway storage, low-cardinality metrics, a Feign-timeout fixture, scripted Agent decisions, and Testcontainers verification.

## Capabilities

### New Capabilities
- `incident-investigation`: Escalate clusters into bounded, auditable Deep Investigations and expose their status and reports.
- `evidence-governed-rca`: Model and enforce evidence-supported incident hypotheses and report review outcomes.
- `investigation-toolsets`: Load deterministic JVM Skills and safely execute typed, read-only Toolsets.
- `incident-evaluation`: Evaluate Deep Investigation behavior using repeatable fixtures and scripted decisions.

### Modified Capabilities
- `runtime-platform`: Preserve zero-infrastructure startup while allowing the independently enabled Incident PostgreSQL feature to activate its datasource and migrations.

## Impact

- New Java packages for Incident domain, runtime, persistence, Toolsets, Skills, web APIs, configuration, metrics, fixtures, and tests.
- New Flyway, JDBC/JPA, PostgreSQL driver, and Testcontainers dependencies; PostgreSQL is required only when `stackwatch.incident.enabled=true`.
- Existing `ErrorAnalyzer` gains non-blocking escalation after a completed Fast Path result, but its response type and L1/L2/L3 behavior remain compatible.
- New `/incidents` POST and read-only GET endpoints; no remediation endpoint or production write operation is introduced.

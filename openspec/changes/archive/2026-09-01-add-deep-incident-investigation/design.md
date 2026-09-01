## Context

The current system merges Java errors through L1 fingerprints, L2 similarity, and L3 lightweight LLM RCA. L3 is synchronous and its `RootCauseAnalysis` holds an unstructured list of evidence strings. Existing Fast Path behavior must stay cheap, synchronous, and operational without a database. Issue #2 requires a separate JVM Incident Intelligence capability that can inspect multiple read-only signals, retain audit facts, and evaluate the investigation deterministically.

## Goals / Non-Goals

**Goals:**

- Add an opt-in, bounded Deep Investigation that consumes completed Fast Path clusters without changing the Fast Path response contract.
- Persist Incidents, triggers, decisions, tool results, observations, evidence, hypotheses, and reports in PostgreSQL.
- Provide deterministic Skills, safe typed Toolsets, evidence governance, APIs, metrics, and a CI-repeatable Feign-timeout evaluation.

**Non-Goals:**

- Automated remediation, approval workflows, arbitrary commands/SQL, Kubernetes writes, or a policy engine for writes.
- General workflow orchestration, checkpoint/recovery, queue infrastructure, cross-cluster correlation, multi-agent coordination, or live external adapters in this change.
- Altering existing L1/L2/L3 behavior, replacing `RootCauseAnalysis`, or requiring PostgreSQL when the Incident feature is off.

## Decisions

### D1: Separate Fast Path and Deep Path

- **Choice**: `ErrorAnalyzer` completes normally, then best-effort requests escalation for a qualifying completed cluster. Incident execution happens asynchronously and independently.
- **Reason**: Fast Path latency, cache economics, and LLM fallback behavior must remain stable.
- **Alternatives considered**: Running investigation inline would block callers; embedding all evidence in L3 cannot produce an auditable multi-step record.

### D2: Incident identity and lifecycle

- **Choice**: An Active Incident is unique by application, environment, and cluster ID. It has `PENDING`, `RUNNING`, `COMPLETED`, `NEEDS_HUMAN_REVIEW`, and `FAILED` states. Triggers received while active append to the same Incident.
- **Reason**: This deduplicates error storms without adding cross-cluster correlation.
- **Alternatives considered**: One Incident per event is noisy; automatic cross-cluster merging is premature.

### D3: PostgreSQL persistence with independent opt-in

- **Choice**: Store Deep Path records in a Flyway-managed `stackwatch_incident` schema. `stackwatch.incident.enabled` is false by default and independent of L2.
- **Reason**: Auditability requires a durable system of record but default startup must remain infrastructure-free.
- **Alternatives considered**: In-memory records lose audit history; tying the feature to PgVector prevents independent adoption.

### D4: StackWatch-owned bounded Agent Loop

- **Choice**: The runtime stores explicit decision summaries and validates each structured `AgentDecision` against state, limits, Skill policy, and registered Toolsets. Defaults are six steps, three calls, five seconds per call, and 30 seconds total, all externalized.
- **Reason**: The model assists reasoning but cannot own business state or evade operational limits.
- **Alternatives considered**: Framework-managed agent state is not auditable domain state; unbounded loops create cost and safety risk.

### D5: Evidence model and report separation

- **Choice**: Raw Tool Results are normalized into Observations. Only redacted summary, provenance, time range, and content hash persist. `IncidentReport` contains `Hypothesis[]`, `Evidence[]`, missing evidence, recommendation, and review result; it does not extend `RootCauseAnalysis`.
- **Reason**: This retains source traceability without persisting model chain-of-thought or making the Incident database a raw sensitive-data store.
- **Alternatives considered**: Reusing string evidence breaks structural traceability; full raw storage increases security and retention risk.

### D6: Evidence confirmation gate

- **Choice**: Two independent evidence sources are required for `VERIFIED`; a single source is `PROVISIONAL` and needs human review; no valid evidence produces `UNKNOWN` and needs review.
- **Reason**: A concrete, testable threshold prevents a plausible LLM explanation from being presented as confirmed RCA.
- **Alternatives considered**: Confidence-only gates retain hallucination risk; requiring every source blocks useful provisional investigation.

### D7: Deterministic Skills and typed read-only Toolsets

- **Choice**: Load `SKILL.md` resources from `src/main/resources/incident-skills`; match them deterministically from exception signals and Fast Path RCA. Use `Toolset → ToolRegistry → ToolExecutor` with normalized ToolResult contracts, separate from current `AnalysisTools`.
- **Reason**: Procedural knowledge stays distinct from memory and all calls can be policy-scoped, audited, and tested.
- **Alternatives considered**: LLM-selected Skills are non-deterministic; reusing Spring AI `@Tool` makes auditing and adapter replacement harder.

### D8: Local-first adapters and execution reliability

- **Choice**: Ship Logs, Trace, and Git/Deployment Stub Adapters plus configuration seams. Run asynchronous work in a local TaskExecutor with no recovery; restart marks stale `RUNNING` records `FAILED`. Tool failure is an Observation and missing evidence; it does not abort other permissible investigation steps.
- **Reason**: The end-to-end contract is demonstrable without inaccessible production dependencies, while failures remain honest and safe.
- **Alternatives considered**: Requiring live providers blocks the change; queue/retry/recovery infrastructure exceeds scope.

### D9: APIs, metrics, and deterministic evaluation

- **Choice**: `POST /incidents` accepts an existing `clusterId` and optional investigation note; GET endpoints return status/steps and report. Metrics use low-cardinality state, trigger, tool, and review labels. A Feign-timeout fixture uses scripted decisions and Stub Adapter results; Testcontainers verifies migrations and repositories.
- **Reason**: Users can request and inspect investigations, while CI proves behavior without an LLM key or live provider.
- **Alternatives considered**: An Incident UI/listing platform and live-model tests add unrelated infrastructure and nondeterminism.

## Risks / Trade-offs

- [Risk] PostgreSQL outage while Incident is enabled → Mitigation: Escalation is best-effort; Fast Path returns normally; manual creation receives a service failure and existing audit data remains intact.
- [Risk] LLM/tool failure leaves no conclusion → Mitigation: Persist the failure as an Observation, record missing evidence, and terminally mark `NEEDS_HUMAN_REVIEW`.
- [Risk] Stub success may differ from provider behavior → Mitigation: keep typed contracts and add real adapters later behind configuration; retain deterministic fixture coverage.
- [Risk] duplicate triggers grow active Incident history → Mitigation: append lightweight triggers only; do not restart or run parallel investigations.
- [Trade-off] no recovery after restart → accepted because generic orchestration and checkpointing are out of scope; the record is preserved and can be manually re-triggered.

## Migration Plan

1. Keep `stackwatch.incident.enabled=false` so existing deployments start unchanged.
2. Add dependencies, feature configuration, migration scripts, and domain/runtime code guarded by the feature flag.
3. Deploy with a PostgreSQL datasource and enable the Incident flag only after Flyway migration and health checks succeed.
4. Validate the Feign-timeout fixture, APIs, metrics, and PostgreSQL integration tests before enabling in a target environment.
5. Roll back by disabling `stackwatch.incident.enabled`; Fast Path remains available. Retain the Incident schema and audit records for inspection rather than deleting data.

## Open Questions

None. Real provider endpoints, authentication/authorization UI, and retention policy beyond redacted persistence are explicitly deferred to follow-up changes.

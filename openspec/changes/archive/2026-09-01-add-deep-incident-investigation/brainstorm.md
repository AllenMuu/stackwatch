# Deep Incident Investigation — validated brainstorming capture

## Background

StackWatch already performs low-cost Java error analysis through fingerprint caching, semantic merging, and lightweight LLM RCA. Issue #2 asks it to evolve into JVM-focused Incident Intelligence without becoming a general AIOps remediation platform.

The existing architecture documents categorically rejected multi-step and persisted agent work. That rule is retained for the default Fast Path, but is deliberately narrowed for an explicitly enabled Deep Path.

## Decision log

1. **Delivery boundary** — Deliver one end-to-end Deep Investigation vertical slice, not a general SRE platform. It covers escalation, investigation, evidence, report, audit, persistence, and evaluation.
2. **Runtime split** — Preserve the synchronous, single-step Fast Path. An opt-in Deep Path is bounded, asynchronous, read-only, and auditable. It excludes remediation, arbitrary shell/SQL/Kubernetes commands, checkpoint/resume, workflow engines, and multi-agent orchestration.
3. **Incident identity** — An Active Incident is unique by `appName + env + clusterId`. Qualifying duplicate triggers append to it; no cross-cluster correlation is in scope.
4. **Escalation** — A completed new L3 cluster, low-confidence or evidence-insufficient RCA, and a human request can create or reuse an Incident. The Fast Path result remains unchanged and never waits for investigation.
5. **Persistence** — Use PostgreSQL from the first implementation, in a Flyway-managed `stackwatch_incident` schema. The Incident feature remains disabled by default and independent of L2 PgVector, although both may share an instance.
6. **Execution** — Persist first, then run with a bounded Spring TaskExecutor. Do not add queues or recovery/resume. A restart marks a RUNNING investigation FAILED; repeat triggers are appended and do not restart or parallelize a run.
7. **Agent control** — StackWatch owns the explicit state, limits, policy validation, and audit trail. An LLM produces a structured AgentDecision only; no model chain-of-thought is persisted.
8. **Evidence governance** — Tool outcomes become Observations. Persist only redacted summaries, provenance, time range, and content hash; raw data remains in source systems. Tool failure becomes missing evidence and does not fabricate facts.
9. **Evidence threshold** — Two independent sources are required for VERIFIED. One source is PROVISIONAL and requires human review. No valid evidence yields UNKNOWN/needs human review.
10. **Skills** — Load three resources from `src/main/resources/incident-skills`: `spring/feign-timeout`, `java/null-pointer`, and `redis/connection-pool-exhaustion`. Use deterministic matching from exception signals and existing RCA; keep procedural Skills separate from Incident Memory.
11. **Toolsets** — Use a typed `Toolset → ToolRegistry → ToolExecutor` distinct from existing Fast Path Spring AI `@Tool`s. Provide Logs, Trace, and Git/Deployment Toolsets with deterministic Stub Adapters and configuration seams for later real read-only adapters.
12. **First fixture** — Model a post-deployment Feign timeout to a downstream order service. Logs, Trace, and Git/Deployment responses establish a deterministic evidence chain and report recommendation only; no corrective action is executed.
13. **APIs and observability** — Provide POST /incidents (by existing clusterId and optional note), plus GET incident status and report endpoints. Add only low-cardinality incident metrics.
14. **Evaluation and failure** — Evaluate versioned local fixtures with scripted AgentDecision responses and no live LLM/external dependency. PostgreSQL migrations and repositories run against Testcontainers. If the Deep Path LLM is unavailable, Fast Path still succeeds and the Incident becomes NEEDS_HUMAN_REVIEW with an audit record.

## Approaches considered

- **General multi-agent AIOps workflow** — rejected: exceeds the JVM investigation scope and conflicts with read-only, bounded operation.
- **Keep all investigation in the existing L3 call** — rejected: cannot retain multi-source evidence, audit steps, or independent tool outcomes.
- **Bounded, StackWatch-owned Deep Investigation** — selected: preserves Fast Path economics while providing evidence-grounded, testable incident investigation.

## Accepted design summary

The system gains an independently enabled PostgreSQL-backed Deep Investigation capability. It consumes completed Fast Path clusters, safely investigates with deterministic Skills and typed read-only Toolsets, creates evidence-grounded reports, and proves the behavior with a versioned Feign timeout fixture. All external access is constrained by deployment configuration; all failure modes preserve Fast Path availability.

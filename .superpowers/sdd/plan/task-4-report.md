# Task 4 implementation report — bounded runtime and Fast Path escalation

## Status

Implemented Task 4.1–4.3 in the `add-deep-incident-investigation` worktree.

## Delivered

- Added structured `AgentDecisionProvider` contracts, immutable `InvestigationContext`, deterministic
  `ScriptedAgentDecisionProvider`, and Spring AI `LlmAgentDecisionProvider`. Only explicit decision
  summaries are accepted; provider prompts and deliberative chain-of-thought are not persisted.
- Added `DeepInvestigationRuntime` with PostgreSQL lifecycle claim/CAS handling, deterministic Skill
  matching, maximum step/tool-call limits, per-tool and total deadlines, and terminal
  `COMPLETED`/`NEEDS_HUMAN_REVIEW`/`FAILED` outcomes. Tool failures and timeouts become normalized
  Observations and missing-evidence entries while permissible later decisions can continue.
- Added `InvestigationScheduler` with in-process duplicate suppression and local asynchronous
  execution. Added feature-guarded wiring with the three local Stub Adapters and LLM provider.
- Added `PostgresIncidentEscalator`; only qualifying L3 or review-required Fast Path results are
  escalated, and production wiring queues persistence asynchronously. `ErrorAnalyzer` preserves its
  existing result and returns normally when escalation or persistence fails; the old constructor
  remains available for unit callers.
- Added focused runtime tests for successful two-source completion, tool-call limits, provider
  failure, and timeout-to-Observation behavior.

## Verification

- `jenv exec mvn -o -DskipTests compile` — passed.
- `git diff --check` — passed.
- The focused runtime test was added, but Maven test compilation is currently blocked by an unrelated
  uncommitted Task 5 `IncidentControllerTest` constructor mismatch (`RootCauseAnalysis`); once that
  parallel work is corrected, run `jenv exec mvn -o -Dtest=DeepInvestigationRuntimeTest test`.

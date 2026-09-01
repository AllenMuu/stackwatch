# Deep Incident Investigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in PostgreSQL-backed, evidence-governed Deep Investigation path while preserving Fast Path behavior.

**Architecture:** New `incident` domain/runtime/persistence packages own durable investigation state. Existing analysis produces clusters as before and performs best-effort escalation; a bounded TaskExecutor invokes typed read-only Toolsets under deterministic Skill policy and persists an IncidentReport.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0, Flyway, PostgreSQL, Testcontainers, JUnit 5, Mockito, Micrometer.

**Spec:** `openspec/changes/add-deep-incident-investigation/{design.md,specs/}`

## Global Constraints

- `stackwatch.incident.enabled` defaults to `false`; Fast Path MUST start and return normally without PostgreSQL.
- Domain types are immutable records; collection and array values require defensive copies.
- LLMs never receive operational write authority and private chain-of-thought is never stored.
- Incident state, tool count, step count, call timeout, and total timeout are enforced by application code.
- Tests for core behavior MUST not require a real LLM key or a live provider.

---

### Task 1: Feature configuration and schema foundation

**Files:**
- Modify: `pom.xml`, `src/main/resources/application.yml`, `src/main/java/com/stackwatch/config/StackWatchConfig.java`
- Create: `src/main/java/com/stackwatch/config/IncidentProperties.java`, `src/main/resources/db/migration/V1__create_incident_schema.sql`, `src/test/java/com/stackwatch/incident/IncidentPostgresIntegrationTest.java`

- [ ] **Step 1:** Write configuration tests proving Incident is disabled by default and properties reject invalid limits.
- [ ] **Step 2:** Run `mvn test -Dtest=IncidentPropertiesTest` and confirm the test fails before the configuration type exists.
- [ ] **Step 3:** Add Flyway, JDBC/JPA, PostgreSQL, and Testcontainers dependencies; add the guarded Incident datasource/configuration and `IncidentProperties` record.
- [ ] **Step 4:** Add schema migration tables for incidents, triggers, steps, observations, evidence, hypotheses, and reports in `stackwatch_incident`.
- [ ] **Step 5:** Write a Testcontainers migration smoke test and run `mvn test -Dtest=IncidentPostgresIntegrationTest`.
- [ ] **Step 6:** Commit with `feat(incident): add PostgreSQL incident foundation`.

### Task 2: Immutable Incident domain and repositories

**Files:**
- Create: `src/main/java/com/stackwatch/incident/domain/{Incident,IncidentStatus,IncidentTrigger,InvestigationStep,Observation,Evidence,Hypothesis,IncidentReport,AgentDecision}.java`
- Create: `src/main/java/com/stackwatch/incident/repository/{IncidentRepository,PostgresIncidentRepository}.java`
- Test: `src/test/java/com/stackwatch/incident/{IncidentDomainTest,PostgresIncidentRepositoryTest}.java`

- [ ] **Step 1:** Write failing domain tests for active-key deduplication, immutable collections, allowed transitions, trigger appending, and two-source verification.
- [ ] **Step 2:** Implement records/enums and transition helpers; run `mvn test -Dtest=IncidentDomainTest`.
- [ ] **Step 3:** Write repository integration tests for create-or-reuse, audit persistence, report lookup, and stale RUNNING failure marking.
- [ ] **Step 4:** Implement repository SQL/mapping with UUID IDs and transactional create-or-reuse semantics; run repository tests.
- [ ] **Step 5:** Commit with `feat(incident): persist incident audit model`.

### Task 3: Skills and safe Toolset contracts

**Files:**
- Create: `src/main/resources/incident-skills/{spring/feign-timeout,java/null-pointer,redis/connection-pool-exhaustion}/SKILL.md`
- Create: `src/main/java/com/stackwatch/incident/{skills,toolset}/...`
- Test: `src/test/java/com/stackwatch/incident/{SkillMatcherTest,ToolExecutorTest}.java`

- [ ] **Step 1:** Write failing tests for SKILL.md loading, deterministic Feign matching, and Toolset policy rejection.
- [ ] **Step 2:** Implement `IncidentSkill`, loader, matcher, `Toolset`, `ToolRegistry`, `ToolExecutor`, `ToolRequest`, and normalized `ToolResult` types.
- [ ] **Step 3:** Add deterministic Logs, Trace, and Git/Deployment Stub Adapters; normalize/redact output before Observation conversion.
- [ ] **Step 4:** Add tests that arbitrary URL, credentials, shell, SQL, and Kubernetes command inputs are rejected and tool failures become Observations.
- [ ] **Step 5:** Run focused tests and commit with `feat(incident): add deterministic skills and toolsets`.

### Task 4: Bounded runtime and Fast Path escalation

**Files:**
- Create: `src/main/java/com/stackwatch/incident/runtime/{DeepInvestigationRuntime,InvestigationScheduler,AgentDecisionProvider,ScriptedAgentDecisionProvider,LlmAgentDecisionProvider}.java`
- Modify: `src/main/java/com/stackwatch/analyzer/ErrorAnalyzer.java`
- Test: `src/test/java/com/stackwatch/incident/DeepInvestigationRuntimeTest.java`, `src/test/java/com/stackwatch/analyzer/ErrorAnalyzerUnitTest.java`

- [ ] **Step 1:** Write runtime tests for max limits, valid tool execution, failures, LLM failure, and terminal review states using scripted decisions.
- [ ] **Step 2:** Implement state validation and TaskExecutor scheduling; persist decision summaries only and convert provider/tool errors to audit outcomes.
- [ ] **Step 3:** Add a feature-guarded escalation collaborator to `ErrorAnalyzer` after its existing result creation; test no change to Fast Path result and no exception propagation on escalation failure.
- [ ] **Step 4:** Run focused runtime/analyzer tests and commit with `feat(incident): run bounded deep investigations`.

### Task 5: HTTP APIs, metrics, and evaluation fixture

**Files:**
- Create: `src/main/java/com/stackwatch/incident/web/{IncidentController,CreateIncidentRequest,IncidentResponse,IncidentReportResponse}.java`
- Create: `src/main/java/com/stackwatch/incident/eval/{FeignTimeoutFixture,IncidentEvaluator}.java`
- Modify: `src/main/java/com/stackwatch/metrics/AnalysisMetrics.java`
- Test: `src/test/java/com/stackwatch/incident/{IncidentControllerTest,IncidentEvaluatorTest}.java`

- [ ] **Step 1:** Write controller tests for POST reuse/create and GET status/report behavior.
- [ ] **Step 2:** Implement endpoints that accept only cluster ID and optional note, plus service lookup behavior.
- [ ] **Step 3:** Add low-cardinality counters/timers for trigger, lifecycle state, tool result, evidence count, review outcome, and evaluation result; test meter tags contain no IDs or messages.
- [ ] **Step 4:** Implement Feign-timeout fixture with Logs, Trace, and Git/Deployment responses and scripted decisions; assert required Toolsets, cited evidence, report, and review status.
- [ ] **Step 5:** Run controller/evaluator tests and commit with `feat(incident): expose and evaluate investigations`.

### Task 6: Documentation and verification

**Files:**
- Modify: `README.md`, `README_zh.md`, `docs/guide/architecture.md`, `docs/guide/getting-started.md`
- Verify: `openspec/changes/add-deep-incident-investigation/tasks.md`

- [ ] **Step 1:** Document enabled/disabled behavior, PostgreSQL/Flyway setup, read-only safety boundary, APIs, Stub Adapter limitation, and no-recovery semantics.
- [ ] **Step 2:** Run `mvn test` under JDK 21, then run Incident Testcontainers tests where Docker is available.
- [ ] **Step 3:** Run `openspec validate --all --json`, resolve every invalid result, and update task checkboxes with completed work during apply.
- [ ] **Step 4:** Commit with `docs: document deep incident investigation`.

## Plan self-review

- Specs are covered by Tasks 1–5; default startup and configuration are covered by Task 1 and Task 4; documentation and full validation are Task 6.
- No placeholder markers remain. Public interfaces and file paths are named before dependent tasks.
- The implementation remains one bounded vertical slice; real provider adapters, remediation, recovery, and UI remain excluded.

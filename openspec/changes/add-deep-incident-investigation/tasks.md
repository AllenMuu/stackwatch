## 1. Feature configuration and PostgreSQL foundation

- [x] 1.1 Add independently disabled Incident configuration, datasource activation rules, Flyway, PostgreSQL driver, and Testcontainers dependencies.
- [x] 1.2 Create Flyway migrations for the `stackwatch_incident` schema and PostgreSQL-backed repository integration tests.
- [x] 1.3 Preserve and test zero-infrastructure startup when Incident and L2 are disabled.

## 2. Incident domain and persistence

- [x] 2.1 Add immutable Incident, trigger, step, observation, evidence, hypothesis, report, status, and decision domain types.
- [x] 2.2 Implement PostgreSQL repositories for Active Incident deduplication, audit persistence, report retrieval, and stale-running failure marking.
- [x] 2.3 Add unit tests for identity, lifecycle transitions, trigger append behavior, and evidence confirmation rules.

## 3. Skills, Toolsets, and evidence governance

- [x] 3.1 Add SKILL.md resource format, loader, deterministic matcher, and the three initial JVM Skills.
- [x] 3.2 Implement typed Toolset contracts, registry, executor, result normalization/redaction, and Logs, Trace, and Git/Deployment Stub Adapters.
- [x] 3.3 Enforce Toolset policy, safe input rejection, failure-to-Observation conversion, and Evidence threshold behavior with tests.

## 4. Bounded Deep Investigation runtime

- [x] 4.1 Add scripted and LLM-backed structured AgentDecision providers with no chain-of-thought persistence.
- [x] 4.2 Implement the bounded asynchronous runtime, configured limits, state validation, and terminal failure/review behavior.
- [x] 4.3 Integrate best-effort escalation after qualifying Fast Path clusters while preserving ErrorAnalyzer return behavior.

## 5. APIs, metrics, and evaluation

- [x] 5.1 Add POST /incidents and read-only Incident status/report endpoints with unit tests.
- [x] 5.2 Add low-cardinality Deep Path Micrometer metrics and tests.
- [x] 5.3 Add the versioned Feign-timeout fixture, scripted evaluation assertions, and documentation for enabling the feature.

## 6. Verification and documentation

- [x] 6.1 Run focused unit tests, PostgreSQL Testcontainers tests, and the full Maven suite under JDK 21.
- [x] 6.2 Update architecture and operational documentation with Deep Path configuration, safety boundaries, and known limitations.

## ADDED Requirements

### Requirement: Repeatable incident evaluation
The system MUST evaluate a versioned Feign-timeout Incident Fixture with scripted AgentDecisions and assert required and forbidden Toolsets, evidence, report outcome, and review state without a live LLM or provider.

#### Scenario: Fixture evaluation succeeds
- **WHEN** the scripted Feign-timeout fixture is evaluated
- **THEN** it MUST invoke Logs, Trace, and Git/Deployment, cite their expected evidence, and produce the expected report outcome

### Requirement: PostgreSQL integration verification
The system MUST verify Flyway migrations and Incident repositories against PostgreSQL Testcontainers while retaining CI-friendly unit tests without infrastructure.

#### Scenario: Repository integration test
- **WHEN** PostgreSQL Testcontainers is available
- **THEN** migrations and Incident persistence operations MUST complete against PostgreSQL

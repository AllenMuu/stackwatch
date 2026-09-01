## ADDED Requirements

### Requirement: Structured incident report
The system MUST keep Fast Path RootCauseAnalysis compatible and MUST persist Deep Path results as a separate IncidentReport with structured hypotheses, evidence references, missing evidence, recommendation, and review outcome.

#### Scenario: Deep investigation completes
- **WHEN** an investigation reaches a terminal outcome
- **THEN** the system MUST make its IncidentReport retrievable by incident ID

### Requirement: Evidence provenance and confirmation gate
The system MUST retain only redacted evidence summaries, provenance, time range, and content hash. A hypothesis MUST be VERIFIED only with two independent evidence sources; one source MUST be PROVISIONAL and require review; zero sources MUST be UNKNOWN and require review.

#### Scenario: One-source hypothesis
- **WHEN** a hypothesis has evidence from only one source
- **THEN** the report MUST mark it PROVISIONAL and NEEDS_HUMAN_REVIEW

#### Scenario: Tool source fails
- **WHEN** a Toolset call fails or times out
- **THEN** the system MUST persist an Observation and missing-evidence entry and MUST NOT represent it as Evidence

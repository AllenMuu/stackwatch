## ADDED Requirements

### Requirement: Incident escalation and deduplication
The system MUST create or reuse an Active Incident after a qualifying completed Fast Path cluster, without changing or delaying the Fast Path result. An Active Incident MUST be unique by application, environment, and cluster ID; new triggers for it MUST be appended.

#### Scenario: New L3 cluster escalates
- **WHEN** an L3 analysis creates a new cluster and Incident investigation is enabled
- **THEN** the system MUST persist an Incident trigger and schedule its investigation asynchronously

#### Scenario: Active incident receives another trigger
- **WHEN** the same application, environment, and cluster qualify while an Incident is PENDING or RUNNING
- **THEN** the system MUST append the trigger and MUST NOT create, restart, or parallelize another investigation

### Requirement: Bounded auditable investigation
The system MUST persist each investigation decision and read-only action, enforce configured limits, and use only the terminal states PENDING, RUNNING, COMPLETED, NEEDS_HUMAN_REVIEW, or FAILED.

#### Scenario: Investigation exceeds a configured limit
- **WHEN** a run reaches its maximum step count, tool-call count, tool timeout, or total timeout
- **THEN** the system MUST stop further actions and persist a terminal review or failure outcome with the reason

### Requirement: Incident API
The system MUST provide POST /incidents for an existing cluster ID and optional note, plus read-only status and report endpoints.

#### Scenario: Human requests existing cluster investigation
- **WHEN** POST /incidents names an existing cluster
- **THEN** the system MUST create or reuse its Active Incident and return its identifier without accepting arbitrary error context

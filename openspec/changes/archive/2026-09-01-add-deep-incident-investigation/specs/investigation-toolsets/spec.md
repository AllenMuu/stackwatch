## ADDED Requirements

### Requirement: Deterministic JVM Skills
The system MUST load versioned SKILL.md resources for Feign timeout, null pointer, and Redis connection-pool exhaustion, and MUST match Skills deterministically from incident signals and Fast Path RCA.

#### Scenario: Feign timeout signals match a Skill
- **WHEN** an Incident contains the configured Feign timeout signals
- **THEN** the runtime MUST select the spring/feign-timeout Skill without an LLM selection call

### Requirement: Safe typed read-only Toolsets
The system MUST execute Logs, Trace, and Git/Deployment Toolsets through a typed registry and executor distinct from Fast Path @Tool methods. It MUST use only deployment-configured read-only sources and fixed query scopes.

#### Scenario: Untrusted operational input is supplied
- **WHEN** an LLM or API caller supplies a URL, credential, shell command, SQL, or Kubernetes command
- **THEN** the Tool Executor MUST reject it without invoking an external system

### Requirement: Local-first adapter contract
The system MUST provide deterministic Stub Adapters for all three Toolsets and configuration seams for future real adapters.

#### Scenario: Fixture invokes Toolsets
- **WHEN** the Feign-timeout fixture runs without external providers
- **THEN** Logs, Trace, and Git/Deployment MUST return normalized deterministic results

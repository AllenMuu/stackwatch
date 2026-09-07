## ADDED Requirements

### Requirement: Raw primary cause-chain capture

The system MUST represent a collected exception as an immutable raw `ThrowableInfo` primary-cause
tree containing exception type, raw message, raw stack-frame strings, and direct cause. Collection
MUST preserve raw values and MUST NOT apply frame classification, message normalization, or
effective-cause selection.

#### Scenario: Direct Throwable contains a meaningful root cause
- **WHEN** a direct `Throwable` has one or more causes
- **THEN** the collected event MUST retain each primary-cause node in outer-to-inner order with its
  own type, message, and raw frames

#### Scenario: Logback proxy contains a meaningful root cause
- **WHEN** a Logback `IThrowableProxy` has a cause
- **THEN** the Logback collector MUST retain the proxy cause chain without rebuilding it as a Java
  wrapper exception

### Requirement: Cause traversal safety

The system MUST traverse no more than 32 primary-cause nodes and MUST stop safely when it detects
an object-identity cycle. It MUST preserve the already captured prefix and MUST NOT fail collection
because the source throwable graph is malformed.

#### Scenario: Cyclic throwable graph
- **WHEN** a source throwable's primary cause path repeats an already visited object
- **THEN** collection MUST stop before duplicating the repeated node and MUST continue to produce an
  analyzable event

#### Scenario: Excessively deep throwable graph
- **WHEN** a source throwable has more than 32 primary-cause nodes
- **THEN** collection MUST retain at most 32 nodes and MUST NOT recurse beyond the configured limit

### Requirement: Backward-compatible HTTP throwable input

The HTTP analysis and collection APIs MUST accept either an optional nested `exception` object that
maps to `ThrowableInfo` or the legacy outer exception fields. A request MUST NOT provide both input
forms, and a legacy request MUST be converted at the API boundary to a one-node raw throwable tree.

#### Scenario: Legacy HTTP request
- **WHEN** an HTTP request provides only `exceptionType`, `exceptionMessage`, and `stackTrace`
- **THEN** the API MUST accept it and analyze an equivalent one-node `ThrowableInfo`

#### Scenario: Structured HTTP request
- **WHEN** an HTTP request provides only the nested `exception` object with a cause chain
- **THEN** the API MUST preserve that chain for preprocessing

#### Scenario: Ambiguous HTTP request
- **WHEN** an HTTP request provides both a nested `exception` object and legacy outer exception
  fields
- **THEN** the API MUST reject the request as invalid rather than choose one representation

# Purpose

Convert raw throwable facts into deterministic normalized error identities while keeping strict
matching authoritative and loose matching non-authoritative.

## Requirements

### Requirement: Explicit deterministic error normalization

The system MUST transform raw `ErrorEvent` facts into an immutable `NormalizedError` before
generating V2 fingerprints. The normalized form MUST retain outer and effective exception types,
normalized outer and root-cause messages, selected normalized frames, and cause depth.

#### Scenario: Normalization is repeatable
- **WHEN** two raw events contain equivalent raw throwable facts
- **THEN** normalization MUST produce equivalent normalized fields independent of event ID, occurrence time, and source collector

### Requirement: Effective-cause and frame selection

The system MUST select the deepest typed non-wrapper node from the primary cause chain as the
effective exception. It MUST select fingerprint frames in this order: effective-cause application
frames, outer application frames, effective-cause raw frames, then outer raw frames.

#### Scenario: Wrapper-equivalent failures
- **WHEN** equivalent failures are wrapped by `CompletionException` or `ExecutionException`
- **THEN** the system MUST select the same underlying effective exception type and selected frames when their normalized cause facts match

#### Scenario: Root cause lacks application frames
- **WHEN** the effective cause has no application frames and the outer exception has application frames
- **THEN** the system MUST select the outer application frames before falling back to raw framework frames

### Requirement: Application package policy

The system MUST use configured application-package prefixes as an allowlist when at least one
prefix is configured. When the allowlist is empty, it MUST use the documented framework denylist
fallback. Line-number-only changes in selected frames MUST NOT change their normalized rendering.

#### Scenario: Configured application prefix
- **WHEN** `application-packages` contains `com.example` and a stack includes `com.example` plus a third-party `com.vendor` class
- **THEN** only the `com.example` class MUST be treated as an application frame

#### Scenario: No configured application prefix
- **WHEN** `application-packages` is empty
- **THEN** the existing framework denylist fallback MUST continue to exclude documented framework prefixes

### Requirement: Versioned semantic message normalization

The V2 message normalizer MUST deterministically replace volatile UUID, IP, date/time, long hash,
long business-ID, and selected URL-query values with documented placeholders. It MUST preserve
stable HTTP status, SQLState, errno, gRPC status, and configured application error codes.

#### Scenario: Volatile order identifier
- **WHEN** messages differ only by a long order identifier
- **THEN** their V2 normalized message templates MUST be equal

#### Scenario: Semantic HTTP discriminator
- **WHEN** otherwise equivalent messages contain HTTP 401 and HTTP 500
- **THEN** their V2 normalized message templates MUST remain distinguishable

### Requirement: Strict and loose V2 fingerprints

The system MUST generate a versioned strict V2 fingerprint from application name, effective
exception type, normalized message template, and selected normalized frames. It MUST generate a
loose V2 fingerprint from the same fields except the message template. Both fingerprints MUST
retain explainable canonical record parts.

#### Scenario: Strict fingerprint scopes identity to an application
- **WHEN** two applications emit identical normalized exception facts
- **THEN** their strict V2 fingerprints MUST be different

#### Scenario: Message-only variation
- **WHEN** two events have identical application, effective type, and selected frames but different normalized message templates
- **THEN** their strict V2 fingerprints MUST differ and their loose V2 fingerprints MUST match

### Requirement: Loose fingerprints are non-authoritative

The analysis path MUST NOT return a prior RCA, merge occurrence counts, or skip L2/L3 solely because
a loose fingerprint matches.

#### Scenario: Loose candidate differs semantically
- **WHEN** an event has a loose match but no strict match
- **THEN** the system MUST continue through the normal L2/L3 fallback path rather than reuse the loose candidate's RCA

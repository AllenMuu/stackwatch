## ADDED Requirements

### Requirement: Opt-in durable error-group history

The system MUST enable durable error-group history only when
`stackwatch.error-history.enabled=true`. When enabled, PostgreSQL MUST be the source of truth for
exact identity and reused RCA data; when disabled, the application MUST retain zero-infrastructure
startup and MUST NOT require an error-history datasource or migration.

#### Scenario: Default startup without error history
- **WHEN** `stackwatch.error-history.enabled` is false or absent and no database is configured
- **THEN** the application MUST start and use non-durable in-memory exact-match behavior

#### Scenario: Enabled durable history
- **WHEN** `stackwatch.error-history.enabled=true` and its datasource is configured
- **THEN** the system MUST run the error-history migration and use the durable repository for exact
  identity lookup

### Requirement: Versioned composite exact identity

The durable repository and Caffeine accelerator MUST key an exact group by application name,
fingerprint version, and strict fingerprint. The durable schema MUST enforce a unique constraint on
that composite identity and MUST retain the loose fingerprint, normalized identity facts, RCA,
cluster link, first seen, last seen, occurrence count, and audit timestamps.

#### Scenario: Same strict hash in different applications
- **WHEN** two otherwise equal strict hashes are stored for different application names
- **THEN** the repository MUST retain separate error groups

#### Scenario: Different fingerprint versions
- **WHEN** a V1 group and a V2 group have the same hash text for one application
- **THEN** the repository MUST retain separate versioned groups

### Requirement: Idempotent atomic occurrence recording

The durable repository MUST atomically register an optional external event ID and update its group.
For each new event ID, it MUST increment occurrence count once, set first seen to the earliest
accepted event time, and set last seen to the latest accepted event time. A duplicate event ID for
the same application MUST return the existing group without another count increment.

#### Scenario: Retried external event
- **WHEN** the same application submits the same event ID twice
- **THEN** the group occurrence count MUST increase only once

#### Scenario: Out-of-order distinct events
- **WHEN** two distinct accepted events arrive out of chronological order
- **THEN** the group first seen and last seen values MUST equal the minimum and maximum event times
  respectively

### Requirement: Exact-hit routing records occurrences

When durable history is enabled, every strict exact hit from either Caffeine or persistent lookup
MUST record the accepted occurrence before returning the stored RCA. Caffeine MUST accelerate lookup
but MUST NOT bypass the durable occurrence mutation.

#### Scenario: Caffeine exact hit
- **WHEN** an event has a strict exact hit in Caffeine and a new event ID
- **THEN** the durable group count and last seen MUST be updated before the analysis result is
  returned

#### Scenario: Cache eviction or process restart
- **WHEN** the cache is empty after eviction or process restart but the durable exact group exists
- **THEN** the system MUST reuse the stored RCA, record the accepted occurrence, and warm Caffeine

### Requirement: V2-primary compatible lookup

The system MUST write newly created durable groups as V2 and MUST look up V2 before any V1 group.
If a V1 group is found, the system MUST treat it as read-only with respect to migration/re-keying;
it MAY record an accepted occurrence on that V1 group, but MUST NOT silently migrate or merge it into V2.

#### Scenario: Existing V1 group
- **WHEN** no V2 exact group exists and a V1 exact group is available
- **THEN** the system MUST return the V1 group's stored RCA without rewriting it as V2

### Requirement: Persistence failure degradation

The system MUST log and instrument an error-history persistence failure and MUST continue through
the non-durable L2/L3 analysis fallback. It MUST NOT report that a durable occurrence update
succeeded when the durable transaction failed.

#### Scenario: Error-history database unavailable
- **WHEN** a durable lookup or occurrence transaction fails
- **THEN** collection MUST still return a best-effort analysis result and observability MUST identify
  the history degradation

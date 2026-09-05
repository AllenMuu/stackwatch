# Task 3 report

## Status

DONE_WITH_CONCERNS

## Changed files

- Added `ErrorHistoryProperties` with an opt-in flag and independent datasource settings.
- Added immutable `ErrorGroup`, `ErrorGroupKey`, `RecordOccurrenceCommand`, and
  `RecordOccurrenceResult` models.
- Added `ErrorGroupRepository` and idempotent in-memory implementation.
- Added conditional history datasource/Flyway configuration and additive PostgreSQL migration.
- Added `ErrorHistoryDisabledStartupTest` covering zero-infrastructure startup, key validation,
  and in-memory recording.

## TDD evidence

- RED: `mvn test -Dtest=ErrorHistoryDisabledStartupTest -q` failed at test compilation because
  the history properties and models did not exist.
- GREEN: the same focused command passed (3 tests).
- Full suite: `mvn test -q` passed (existing Docker/LLM conditional behavior remained unchanged).

## Self-review

- Default `stackwatch.error-history.enabled=false` selects in-memory history and creates no
  datasource. Durable datasource/Flyway beans are conditional on the opt-in flag.
- Collections are defensively copied; occurrence updates return new immutable groups.
- In-memory duplicate external event IDs are scoped by application and do not increment twice.
- PostgreSQL repository behavior is intentionally not implemented; Task 4 owns it.

## Commit

`ba14c4ac55b6b42293bdb02b90e0ba4f6f9133dc`

## Concerns

- The enabled-history Flyway path has not been exercised against PostgreSQL because Docker is
  unavailable; verify migration wiring with the Task 4 integration test.

# Error Group Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a durable, explainable V2 exact-error identity layer with full cause-chain ingestion and idempotent occurrence accounting.

**Architecture:** Collectors produce raw `ThrowableInfo`; preprocessing produces `NormalizedError` and strict/loose V2 fingerprints; `ErrorAnalyzer` uses a typed exact key to consult Caffeine and, when enabled, PostgreSQL `ErrorGroup` history before L2/L3. The database transaction owns occurrence deduplication and group counters; Caffeine only accelerates lookup.

**Tech Stack:** Java 21 records, Spring Boot 4.1, Spring JDBC/Flyway, PostgreSQL, Caffeine, JUnit 5, Mockito, Testcontainers.

**Spec:** `openspec/changes/evolve-error-group-identity/design.md`; `openspec/changes/evolve-error-group-identity/specs/throwable-cause-ingestion/spec.md`; `openspec/changes/evolve-error-group-identity/specs/normalized-error-fingerprints/spec.md`; `openspec/changes/evolve-error-group-identity/specs/durable-error-groups/spec.md`

## Global Constraints

- JDK 21, Spring Boot 4.1.0, and Spring AI 2.0.0 remain the runtime baseline.
- All domain values are immutable records; lists/maps are defensively copied and arrays are cloned.
- Google Java Style applies; public methods use fewer than five parameters, so mutation input is a command record.
- LLM secrets remain environment variables only; pure unit tests never require `DASHSCOPE_API_KEY`.
- Default startup has no database, Kafka, or vector requirement; `stackwatch.error-history.enabled` defaults to false.
- Persistent history failure is best effort: expose degradation through logs/metrics and preserve L2/L3 fallback.

---

### Task 1: Raw throwable contract and ingress conversion

**Files:**
- Create: `src/main/java/com/stackwatch/domain/ThrowableInfo.java`
- Modify: `src/main/java/com/stackwatch/domain/ErrorEvent.java`
- Modify: `src/main/java/com/stackwatch/web/AnalyzeRequest.java`
- Modify: `src/main/java/com/stackwatch/collector/ErrorEventCollector.java`
- Modify: `src/main/java/com/stackwatch/collector/LogbackErrorAppender.java`
- Test: `src/test/java/com/stackwatch/collector/ErrorEventCollectorTest.java`
- Test: `src/test/java/com/stackwatch/collector/CollectControllerTest.java`

**Interfaces:**
- Consumes: existing legacy `AnalyzeRequest` fields and collector entry points.
- Produces: `ThrowableInfo(String type, String message, List<String> stackTrace, ThrowableInfo cause)` and an `ErrorEvent` that carries `ThrowableInfo exception`.

- [ ] **Step 1: Write failing collector and HTTP-contract tests**

```java
assertEquals("java.sql.SQLException", event.exception().cause().type());
assertThrows(MethodArgumentNotValidException.class, () -> controller.collect(mixedRequest));
```

Cover legacy conversion, nested JSON cause preservation, mixed-form rejection, direct `Throwable` cause extraction, a self-cycle, and a 33-node chain.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ErrorEventCollectorTest,CollectControllerTest`

Expected: FAIL because `ThrowableInfo` and structured HTTP input do not exist.

- [ ] **Step 3: Write minimal ingress implementation**

```java
public record ThrowableInfo(String type, String message, List<String> stackTrace, ThrowableInfo cause) {
    public ThrowableInfo { stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace); }
}
private ThrowableInfo fromThrowable(Throwable source, IdentityHashMap<Throwable, Boolean> seen, int depth) {
    if (source == null || depth == 32 || seen.put(source, Boolean.TRUE) != null) return null;
    return new ThrowableInfo(source.getClass().getName(), source.getMessage(),
        Arrays.stream(source.getStackTrace()).map(Object::toString).toList(),
        fromThrowable(source.getCause(), seen, depth + 1));
}
```

Implement equivalent `IThrowableProxy` traversal. Map legacy fields to one node at the controller boundary and reject both-form input.

- [ ] **Step 4: Run ingress tests to verify passing behavior**

Run: `mvn test -Dtest=ErrorEventCollectorTest,CollectControllerTest`

Expected: PASS with no recursive overflow and no nested-cause data loss.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stackwatch/domain src/main/java/com/stackwatch/web src/main/java/com/stackwatch/collector src/test/java/com/stackwatch/collector
git commit -m "feat: capture structured throwable cause chains"
```

### Task 2: Normalization policy and V2 fingerprint rendering

**Files:**
- Create: `src/main/java/com/stackwatch/preprocess/NormalizedError.java`
- Create: `src/main/java/com/stackwatch/preprocess/CauseResolver.java`
- Create: `src/main/java/com/stackwatch/preprocess/MessageNormalizer.java`
- Create: `src/main/java/com/stackwatch/preprocess/StackFrameNormalizer.java`
- Create: `src/main/java/com/stackwatch/preprocess/ErrorNormalizer.java`
- Create: `src/main/java/com/stackwatch/config/FingerprintProperties.java`
- Modify: `src/main/java/com/stackwatch/preprocess/Fingerprinter.java`
- Modify: `src/main/java/com/stackwatch/domain/ErrorFingerprint.java`
- Modify: `src/main/java/com/stackwatch/config/StackWatchConfig.java`
- Test: `src/test/java/com/stackwatch/preprocess/ErrorNormalizerTest.java`
- Test: `src/test/java/com/stackwatch/preprocess/FingerprinterTest.java`

**Interfaces:**
- Consumes: `ErrorEvent.exception()` and `FingerprintProperties`.
- Produces: `NormalizedError`, strict/loose `ErrorFingerprint`, and canonical record parts.

- [ ] **Step 1: Write failing normalization fixtures**

```java
assertEquals("java.sql.SQLException", normalizer.normalize(wrapped).effectiveExceptionType());
assertEquals("Order <NUM> not found", messageNormalizer.normalize("Order 981273 not found"));
assertNotEquals(strict401.hash(), strict500.hash());
assertEquals(first.looseHash(), second.looseHash());
```

Include wrappers, frame priority, line numbers, allowlist precedence, UUID/IP/time masking, code preservation, and application-scoped identity.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ErrorNormalizerTest,FingerprinterTest`

Expected: FAIL because normalization is embedded in `Fingerprinter` and V2 has no loose hash.

- [ ] **Step 3: Write minimal normalization and V2 renderer**

```java
public record NormalizedError(String outerExceptionType, String effectiveExceptionType,
    String normalizedMessage, String normalizedRootCauseMessage,
    List<String> applicationFrames, List<String> rootCauseFrames, int causeDepth) { }
public ErrorFingerprint generateV2(NormalizedError error, String appName) {
    String strict = canonical("v2", appName, error.effectiveExceptionType(),
        error.normalizedRootCauseMessage(), error.applicationFrames());
    String loose = canonical("v2", appName, error.effectiveExceptionType(), null, error.applicationFrames());
    return ErrorFingerprint.v2(sha256(strict), sha256(loose), recordParts);
}
```

Keep V1 rendering readable; use configured package prefixes exclusively when nonempty and ordered message patterns that retain explicit status/code tokens.

- [ ] **Step 4: Run preprocessing tests to verify passing behavior**

Run: `mvn test -Dtest=ErrorNormalizerTest,FingerprinterTest`

Expected: PASS; fixture output pins V2 behavior.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stackwatch/preprocess src/main/java/com/stackwatch/config/FingerprintProperties.java src/main/java/com/stackwatch/domain/ErrorFingerprint.java src/main/java/com/stackwatch/config/StackWatchConfig.java src/test/java/com/stackwatch/preprocess
git commit -m "feat: add normalized V2 error fingerprints"
```

### Task 3: Error-history boundary, models, and schema

**Files:**
- Create: `src/main/java/com/stackwatch/config/ErrorHistoryProperties.java`
- Create: `src/main/java/com/stackwatch/history/ErrorGroup.java`
- Create: `src/main/java/com/stackwatch/history/ErrorGroupKey.java`
- Create: `src/main/java/com/stackwatch/history/RecordOccurrenceCommand.java`
- Create: `src/main/java/com/stackwatch/history/ErrorGroupRepository.java`
- Create: `src/main/java/com/stackwatch/history/InMemoryErrorGroupRepository.java`
- Modify: `src/main/java/com/stackwatch/config/StackWatchConfig.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/db/error-history/V1__create_error_history.sql`
- Test: `src/test/java/com/stackwatch/history/ErrorHistoryDisabledStartupTest.java`

**Interfaces:**
- Consumes: typed V2 keys and optional external event IDs.
- Produces: `ErrorGroupRepository.findExact(ErrorGroupKey)` and `ErrorGroupRepository.record(RecordOccurrenceCommand)`.

- [ ] **Step 1: Write failing configuration and model tests**

```java
assertFalse(applicationContext.containsBean("errorHistoryDataSource"));
assertThrows(IllegalArgumentException.class, () -> new ErrorGroupKey("", FingerprintVersion.V2, "hash"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ErrorHistoryDisabledStartupTest`

Expected: FAIL because the history feature flag and typed models do not exist.

- [ ] **Step 3: Write gated configuration and additive schema**

```sql
CREATE TABLE stackwatch_error_history.error_groups (
  id UUID PRIMARY KEY, app_name VARCHAR(255) NOT NULL,
  fingerprint_version VARCHAR(16) NOT NULL, strict_fingerprint CHAR(64) NOT NULL,
  loose_fingerprint CHAR(64) NOT NULL, occurrence_count BIGINT NOT NULL,
  first_seen TIMESTAMPTZ NOT NULL, last_seen TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_error_group_identity UNIQUE (app_name, fingerprint_version, strict_fingerprint)
);
CREATE TABLE stackwatch_error_history.accepted_error_events (
  app_name VARCHAR(255) NOT NULL, event_id VARCHAR(255) NOT NULL,
  group_id UUID NOT NULL, accepted_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (app_name, event_id)
);
```

Bind `stackwatch.error-history.enabled` to false and construct its datasource/Flyway only when true.

- [ ] **Step 4: Run configuration tests to verify passing behavior**

Run: `mvn test -Dtest=ErrorHistoryDisabledStartupTest`

Expected: PASS with no PostgreSQL connection or migration in default configuration.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stackwatch/config src/main/java/com/stackwatch/history src/main/resources/application.yml src/main/resources/db/error-history src/test/java/com/stackwatch/history
git commit -m "feat: add opt-in error history schema"
```

### Task 4: Transactional PostgreSQL ErrorGroup repository

**Files:**
- Create: `src/main/java/com/stackwatch/history/PostgresErrorGroupRepository.java`
- Test: `src/test/java/com/stackwatch/history/PostgresErrorGroupRepositoryTest.java`

**Interfaces:**
- Consumes: `RecordOccurrenceCommand` and JDBC transaction support.
- Produces: `RecordOccurrenceResult(ErrorGroup group, boolean accepted)`.

- [ ] **Step 1: Write failing Testcontainers repository tests**

```java
assertEquals(1, repository.record(command).group().occurrenceCount());
assertFalse(repository.record(command).accepted());
assertEquals(early, repository.record(laterThenEarly).group().firstSeen());
```

Cover group creation, duplicate `(app,eventId)`, out-of-order distinct events, application/version isolation, and lookup through a new repository instance.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PostgresErrorGroupRepositoryTest`

Expected: FAIL because no PostgreSQL history repository exists.

- [ ] **Step 3: Write one-transaction record implementation**

```java
@Transactional
public RecordOccurrenceResult record(RecordOccurrenceCommand command) {
    ErrorGroup group = findOrCreate(command);
    int inserted = insertEventIfAbsent(command.appName(), command.eventId(), group.id(), command.occurredAt());
    if (inserted == 1) group = incrementWithMinMax(group.id(), command.occurredAt());
    return new RecordOccurrenceResult(findById(group.id()).orElseThrow(), inserted == 1);
}
```

Use `INSERT ... ON CONFLICT DO NOTHING`, `LEAST`/ `GREATEST`, and a unique-constraint retry or advisory transaction lock for same-key creation.

- [ ] **Step 4: Run repository tests to verify passing behavior**

Run: `mvn test -Dtest=PostgresErrorGroupRepositoryTest`

Expected: PASS with composite identity and duplicate suppression enforced by PostgreSQL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stackwatch/history/PostgresErrorGroupRepository.java src/test/java/com/stackwatch/history/PostgresErrorGroupRepositoryTest.java
git commit -m "feat: persist idempotent error group occurrences"
```

### Task 5: Typed cache and analyzer routing

**Files:**
- Modify: `src/main/java/com/stackwatch/analyzer/FingerprintCache.java`
- Modify: `src/main/java/com/stackwatch/analyzer/ErrorAnalyzer.java`
- Modify: `src/main/java/com/stackwatch/domain/AnalysisResult.java`
- Modify: `src/main/java/com/stackwatch/metrics/AnalysisMetrics.java`
- Test: `src/test/java/com/stackwatch/analyzer/ErrorAnalyzerUnitTest.java`

**Interfaces:**
- Consumes: `ErrorNormalizer`, `ErrorGroupRepository`, `ErrorGroupKey`, and V2 `ErrorFingerprint`.
- Produces: strict-hit results only after durable occurrence recording, then L2/L3 fallback when no strict group exists.

- [ ] **Step 1: Write failing analyzer routing tests**

```java
verify(errorGroupRepository).record(argThat(command -> command.eventId().equals("evt-1")));
verify(fingerprintCache).put(eq(key), eq(group));
verify(chatClient, never()).prompt();
verify(errorGroupRepository, never()).findByLooseFingerprint(anyString());
```

Cover Caffeine strict hits, repository hits after cache miss, V1 fallback, L2/L3 first-group creation, history-disabled mode, loose-match non-reuse, and history outage fallback.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ErrorAnalyzerUnitTest`

Expected: FAIL because cached values are bare RCAs and exact hits return before occurrence recording.

- [ ] **Step 3: Write typed cache and routing implementation**

```java
Optional<ErrorGroup> cached = fingerprintCache.lookup(key);
Optional<ErrorGroup> group = cached.or(() -> history.findExact(key));
if (group.isPresent()) {
    RecordOccurrenceResult recorded = history.record(RecordOccurrenceCommand.existing(group.get(), event));
    fingerprintCache.put(key, recorded.group());
    return AnalysisResult.cacheHit(key.strictFingerprint(), recorded.group().analysis());
}
```

Normalize once per event, attempt V2 then V1 read-only strict lookup, persist V2 groups for resolved L2/L3 outcomes before cache warmup, and catch history errors to emit a degradation metric before continuing L2/L3. Do not branch on loose matches.

- [ ] **Step 4: Run analyzer tests to verify passing behavior**

Run: `mvn test -Dtest=ErrorAnalyzerUnitTest`

Expected: PASS without a real LLM key and without automatic RCA reuse from loose fingerprints.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stackwatch/analyzer/FingerprintCache.java src/main/java/com/stackwatch/analyzer/ErrorAnalyzer.java src/main/java/com/stackwatch/domain/AnalysisResult.java src/main/java/com/stackwatch/metrics/AnalysisMetrics.java src/test/java/com/stackwatch/analyzer/ErrorAnalyzerUnitTest.java
git commit -m "feat: route exact matches through durable error groups"
```

### Task 6: Documentation and end-to-end verification

**Files:**
- Modify: `README.md`
- Modify: `docs/guide/architecture.md`
- Modify: `docs/guide/getting-started.md`
- Test: `src/test/java/com/stackwatch/history/PostgresErrorGroupRepositoryTest.java`
- Test: `src/test/java/com/stackwatch/incident/IncidentDisabledStartupTest.java`

**Interfaces:**
- Consumes: completed feature flags, repository behavior, and tests from Tasks 1–5.
- Produces: deployable configuration guidance and captured verification results.

- [ ] **Step 1: Write documentation acceptance checklist**

```text
Default command: mvn spring-boot:run
Durable mode: set stackwatch.error-history.enabled=true and stackwatch.error-history.datasource.*
Producer rule: send a stable eventId to obtain retry-safe counting
Deferred scope: raw occurrence retention, restart-safe L2, and V1 retirement are GitHub #6
```

- [ ] **Step 2: Run targeted verification before documentation update**

Run: `mvn test -Dtest=ErrorNormalizerTest,FingerprinterTest,ErrorAnalyzerUnitTest,PostgresErrorGroupRepositoryTest,ErrorHistoryDisabledStartupTest`

Expected: PASS; failures are fixed in their owning task before documentation claims behavior.

- [ ] **Step 3: Update operator-facing guidance**

Document raw-to-normalized-to-identity flow, history enablement, independent datasource, package allowlist, stable event-ID requirement, V2-primary/V1-read-only behavior, and GitHub #6 boundary.

- [ ] **Step 4: Run the full regression suite**

Run: `mvn test`

Expected: PASS without `DASHSCOPE_API_KEY`; Testcontainers-dependent tests skip only under their established environment condition.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/guide/architecture.md docs/guide/getting-started.md src/test
git commit -m "docs: document durable error identity history"
```

## Plan Self-Review

- Spec coverage: Tasks 1–2 cover throwable ingestion and normalized fingerprints; Tasks 3–5 cover durable groups; Task 6 verifies default and durable modes.
- Placeholder scan: no unresolved implementation markers or generic test instructions remain.
- Type consistency: `ThrowableInfo`, `NormalizedError`, `ErrorGroupKey`, `RecordOccurrenceCommand`, `ErrorGroupRepository`, and typed cache entries are introduced before dependent tasks use them.

package com.stackwatch.incident.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwatch.incident.domain.AgentDecision;
import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Hypothesis;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.InvestigationStep;
import com.stackwatch.incident.domain.Observation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation for the append-only Deep Path audit model. */
@Repository
@ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
public class PostgresIncidentRepository implements IncidentRepository {

    private static final String STALE_FAILURE_REASON =
        "Investigation was marked stale and failed after process restart";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresIncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * PostgreSQL advisory locking serializes each active identity. The partial unique index remains the
     * final database guard, and one transaction preserves both the trigger append and activity timestamp.
     */
    @Override
    @Transactional
    public Incident createOrReuse(String applicationName, String environment, String clusterId,
                                  IncidentTrigger trigger) {
        String activeKey = activeKey(applicationName, environment, clusterId);
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
            (resultSet, rowNumber) -> Boolean.TRUE, activeKey);

        Optional<Incident> existing = findActive(applicationName, environment, clusterId);
        if (existing.isPresent()) {
            Incident incident = existing.orElseThrow();
            insertTrigger(incident.id(), trigger);
            touchActivity(incident.id(), trigger.createdAt());
            return findById(incident.id()).orElseThrow();
        }

        Incident incident = Incident.pending(UUID.randomUUID(), applicationName, environment, clusterId,
            List.of(trigger), trigger.createdAt());
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.incidents
                (id, application_name, environment, cluster_id, status, created_at, updated_at,
                 started_at, completed_at, failure_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, incident.id(), incident.applicationName(), incident.environment(), incident.clusterId(),
            incident.status().name(), incident.createdAt(), incident.updatedAt(), incident.startedAt(),
            incident.completedAt(), incident.failureReason());
        insertTrigger(incident.id(), trigger);
        return incident;
    }

    @Override
    public Optional<Incident> findById(UUID incidentId) {
        List<IncidentRow> rows = jdbcTemplate.query("""
            SELECT id, application_name, environment, cluster_id, status, created_at, updated_at,
                   started_at, completed_at, failure_reason
            FROM stackwatch_incident.incidents
            WHERE id = ?
            """, incidentRowMapper(), incidentId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        IncidentRow row = rows.getFirst();
        return Optional.of(row.toIncident(findTriggers(incidentId), findSteps(incidentId)));
    }

    /** Atomically claims one pending incident for a worker; all other callers observe an empty result. */
    @Override
    @Transactional
    public Optional<Incident> startIfPending(UUID incidentId, Instant startedAt) {
        Objects.requireNonNull(incidentId, "incidentId is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        int updated = jdbcTemplate.update("""
            UPDATE stackwatch_incident.incidents
            SET status = ?, started_at = ?, updated_at = GREATEST(updated_at, ?),
                completed_at = NULL, failure_reason = NULL
            WHERE id = ? AND status = ?
            """, IncidentStatus.RUNNING.name(), startedAt, startedAt, incidentId,
            IncidentStatus.PENDING.name());
        return updated == 1 ? findById(incidentId) : Optional.empty();
    }

    /**
     * Persists a terminal lifecycle transition only when the stored status still matches the caller's
     * expected state. This prevents stale workers from reviving or overwriting a completed incident.
     */
    @Override
    @Transactional
    public boolean updateIfCurrentStatus(Incident incident, IncidentStatus expectedStatus) {
        Objects.requireNonNull(incident, "incident is required");
        Objects.requireNonNull(expectedStatus, "expectedStatus is required");
        if (!expectedStatus.canTransitionTo(incident.status())) {
            throw new IllegalArgumentException(
                "invalid persisted transition from " + expectedStatus + " to " + incident.status());
        }
        return jdbcTemplate.update("""
            UPDATE stackwatch_incident.incidents
            SET status = ?, updated_at = GREATEST(updated_at, ?), started_at = ?, completed_at = ?,
                failure_reason = ?
            WHERE id = ? AND status = ?
            """, incident.status().name(), incident.updatedAt(), incident.startedAt(),
            incident.completedAt(), incident.failureReason(), incident.id(), expectedStatus.name()) == 1;
    }

    @Override
    @Transactional
    public void appendStep(InvestigationStep step) {
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.steps
                (id, incident_id, sequence_number, decision_type, decision_summary, toolset, outcome,
                 created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, step.id(), step.incidentId(), step.sequenceNumber(), step.decision().decisionType(),
            step.decision().summary(), step.decision().toolset(), step.outcome(), step.createdAt());
        touchActivity(step.incidentId(), step.createdAt());
    }

    @Override
    @Transactional
    public void appendObservation(Observation observation) {
        if (observation.stepId() != null) {
            requireOwnedRecord("steps", observation.stepId(), observation.incidentId());
        }
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.observations
                (id, incident_id, step_id, source_type, status, redacted_summary, provenance,
                 observed_from, observed_to, content_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, observation.id(), observation.incidentId(), observation.stepId(), observation.sourceType(),
            observation.status(), observation.redactedSummary(), observation.provenance(),
            observation.observedFrom(), observation.observedTo(), observation.contentHash(),
            observation.createdAt());
        touchActivity(observation.incidentId(), observation.createdAt());
    }

    @Override
    @Transactional
    public void appendEvidence(Evidence evidence) {
        requireOwnedRecord("observations", evidence.observationId(), evidence.incidentId());
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.evidence
                (id, incident_id, observation_id, source_type, redacted_summary, provenance,
                 observed_from, observed_to, content_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, evidence.id(), evidence.incidentId(), evidence.observationId(), evidence.sourceType(),
            evidence.redactedSummary(), evidence.provenance(), evidence.observedFrom(), evidence.observedTo(),
            evidence.contentHash(), evidence.createdAt());
        touchActivity(evidence.incidentId(), evidence.createdAt());
    }

    @Override
    public List<Observation> findObservations(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT id, incident_id, step_id, source_type, status, redacted_summary, provenance,
                   observed_from, observed_to, content_hash, created_at
            FROM stackwatch_incident.observations
            WHERE incident_id = ?
            ORDER BY created_at, id
            """, observationRowMapper(), incidentId);
    }

    @Override
    public List<Evidence> findEvidence(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT id, incident_id, observation_id, source_type, redacted_summary, provenance,
                   observed_from, observed_to, content_hash, created_at
            FROM stackwatch_incident.evidence
            WHERE incident_id = ?
            ORDER BY created_at, id
            """, evidenceRowMapper(), incidentId);
    }

    @Override
    @Transactional
    public void saveReport(IncidentReport report) {
        IncidentReport authoritativeReport = reportWithPersistedEvidence(report);
        jdbcTemplate.update("""
            DELETE FROM stackwatch_incident.hypothesis_evidence
            WHERE hypothesis_id IN (
                SELECT id FROM stackwatch_incident.hypotheses WHERE incident_id = ?
            )
            """, authoritativeReport.incidentId());
        jdbcTemplate.update(
            "DELETE FROM stackwatch_incident.hypotheses WHERE incident_id = ?",
            authoritativeReport.incidentId());
        for (Hypothesis hypothesis : authoritativeReport.hypotheses()) {
            jdbcTemplate.update("""
                INSERT INTO stackwatch_incident.hypotheses
                    (id, incident_id, statement, status, confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, hypothesis.id(), hypothesis.incidentId(), hypothesis.statement(),
                hypothesis.verificationStatus().name(), hypothesis.confidence(), hypothesis.createdAt());
            for (Evidence citation : hypothesis.citedEvidence()) {
                jdbcTemplate.update("""
                    INSERT INTO stackwatch_incident.hypothesis_evidence (hypothesis_id, evidence_id)
                    VALUES (?, ?)
                    """, hypothesis.id(), citation.id());
            }
        }
        UUID persistedReportId = jdbcTemplate.queryForObject("""
            INSERT INTO stackwatch_incident.reports
                (id, incident_id, recommendation, missing_evidence, review_outcome, created_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (incident_id) DO UPDATE
            SET recommendation = EXCLUDED.recommendation,
                missing_evidence = EXCLUDED.missing_evidence,
                review_outcome = EXCLUDED.review_outcome,
                created_at = EXCLUDED.created_at
            RETURNING id
            """, (resultSet, rowNumber) -> uuid(resultSet, "id"), authoritativeReport.id(),
            authoritativeReport.incidentId(), authoritativeReport.recommendation(),
            toJson(authoritativeReport.missingEvidence()), authoritativeReport.reviewOutcome().name(),
            authoritativeReport.createdAt());
        jdbcTemplate.update(
            "DELETE FROM stackwatch_incident.report_evidence WHERE report_id = ?", persistedReportId);
        for (Evidence item : authoritativeReport.evidence()) {
            jdbcTemplate.update("""
                INSERT INTO stackwatch_incident.report_evidence (report_id, evidence_id)
                VALUES (?, ?)
                """, persistedReportId, item.id());
        }
        touchActivity(authoritativeReport.incidentId(), authoritativeReport.createdAt());
    }

    @Override
    public Optional<IncidentReport> findReport(UUID incidentId) {
        List<ReportRow> rows = jdbcTemplate.query("""
            SELECT id, incident_id, recommendation, missing_evidence, review_outcome, created_at
            FROM stackwatch_incident.reports
            WHERE incident_id = ?
            """, reportRowMapper(), incidentId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReportRow row = rows.getFirst();
        List<Evidence> evidence = findReportEvidence(row.id(), incidentId);
        IncidentReport report = new IncidentReport(row.id(), row.incidentId(),
            findHypotheses(incidentId, evidence), evidence, row.missingEvidence(), row.recommendation(),
            row.reviewOutcome(), row.createdAt());
        if (report.reviewOutcome() != row.reviewOutcome()) {
            throw new IllegalStateException("persisted report review outcome violates the evidence gate");
        }
        return Optional.of(report);
    }

    @Override
    @Transactional
    public int markStaleRunningFailed(Instant staleBefore, Instant now) {
        return jdbcTemplate.update("""
            UPDATE stackwatch_incident.incidents
            SET status = ?, updated_at = ?, completed_at = ?, failure_reason = ?
            WHERE status = ? AND updated_at < ?
            """, IncidentStatus.FAILED.name(), now, now, STALE_FAILURE_REASON,
            IncidentStatus.RUNNING.name(), staleBefore);
    }

    private Optional<Incident> findActive(String applicationName, String environment, String clusterId) {
        List<IncidentRow> rows = jdbcTemplate.query("""
            SELECT id, application_name, environment, cluster_id, status, created_at, updated_at,
                   started_at, completed_at, failure_reason
            FROM stackwatch_incident.incidents
            WHERE application_name = ? AND environment = ? AND cluster_id = ?
              AND status IN ('PENDING', 'RUNNING')
            FOR UPDATE
            """, incidentRowMapper(), applicationName, environment, clusterId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        IncidentRow row = rows.getFirst();
        return Optional.of(row.toIncident(findTriggers(row.id()), findSteps(row.id())));
    }

    private void insertTrigger(UUID incidentId, IncidentTrigger trigger) {
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.triggers (id, incident_id, trigger_type, note, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, trigger.id(), incidentId, trigger.triggerType(), trigger.note(), trigger.createdAt());
    }

    private void touchActivity(UUID incidentId, Instant activityAt) {
        int touched = jdbcTemplate.update("""
            UPDATE stackwatch_incident.incidents
            SET updated_at = GREATEST(updated_at, ?)
            WHERE id = ?
            """, activityAt, incidentId);
        if (touched != 1) {
            throw new IllegalArgumentException("incident does not exist: " + incidentId);
        }
    }

    private void requireOwnedRecord(String table, UUID recordId, UUID incidentId) {
        List<UUID> owners = jdbcTemplate.query(
            "SELECT incident_id FROM stackwatch_incident." + table + " WHERE id = ?",
            (resultSet, rowNumber) -> uuid(resultSet, "incident_id"), recordId);
        if (owners.size() != 1 || !incidentId.equals(owners.getFirst())) {
            throw new IllegalArgumentException(table + " record must belong to incident");
        }
    }

    private IncidentReport reportWithPersistedEvidence(IncidentReport report) {
        Map<UUID, Evidence> evidenceById = new LinkedHashMap<>();
        for (Evidence callerSupplied : report.evidence()) {
            Evidence persisted = loadPersistedEvidence(report.incidentId(), callerSupplied.id());
            if (!hasSamePersistedReference(callerSupplied, persisted)) {
                throw new IllegalArgumentException(
                    "report evidence must retain its persisted observation reference");
            }
            evidenceById.put(persisted.id(), persisted);
        }
        List<Hypothesis> authoritativeHypotheses = report.hypotheses().stream()
            .map(hypothesis -> new Hypothesis(hypothesis.id(), hypothesis.incidentId(),
                hypothesis.statement(), hypothesis.confidence(), hypothesis.citedEvidence().stream()
                    .map(Evidence::id)
                    .map(evidenceById::get)
                    .map(item -> Objects.requireNonNull(item, "report citation is not persisted"))
                    .toList(), hypothesis.createdAt()))
            .toList();
        return new IncidentReport(report.id(), report.incidentId(), authoritativeHypotheses,
            List.copyOf(evidenceById.values()), report.missingEvidence(), report.recommendation(),
            report.reviewOutcome(), report.createdAt());
    }

    private static boolean hasSamePersistedReference(Evidence callerSupplied, Evidence persisted) {
        return callerSupplied.id().equals(persisted.id())
            && callerSupplied.incidentId().equals(persisted.incidentId())
            && callerSupplied.observationId().equals(persisted.observationId());
    }

    private Evidence loadPersistedEvidence(UUID incidentId, UUID evidenceId) {
        List<Evidence> rows = jdbcTemplate.query("""
            SELECT id, incident_id, observation_id, source_type, redacted_summary, provenance,
                   observed_from, observed_to, content_hash, created_at
            FROM stackwatch_incident.evidence
            WHERE id = ? AND incident_id = ?
            """, evidenceRowMapper(), evidenceId, incidentId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("evidence record must belong to incident");
        }
        return rows.getFirst();
    }

    private List<Evidence> findReportEvidence(UUID reportId, UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT evidence.id, evidence.incident_id, evidence.observation_id, evidence.source_type,
                   evidence.redacted_summary, evidence.provenance, evidence.observed_from,
                   evidence.observed_to, evidence.content_hash, evidence.created_at
            FROM stackwatch_incident.report_evidence report_evidence
            JOIN stackwatch_incident.evidence evidence ON evidence.id = report_evidence.evidence_id
            WHERE report_evidence.report_id = ? AND evidence.incident_id = ?
            ORDER BY evidence.created_at, evidence.id
            """, evidenceRowMapper(), reportId, incidentId);
    }

    private List<IncidentTrigger> findTriggers(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT id, trigger_type, note, created_at
            FROM stackwatch_incident.triggers
            WHERE incident_id = ?
            ORDER BY created_at, id
            """, (resultSet, rowNumber) -> new IncidentTrigger(
                uuid(resultSet, "id"), resultSet.getString("trigger_type"), resultSet.getString("note"),
                instant(resultSet, "created_at")), incidentId);
    }

    private List<InvestigationStep> findSteps(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT id, incident_id, sequence_number, decision_type, decision_summary, toolset, outcome,
                   created_at
            FROM stackwatch_incident.steps
            WHERE incident_id = ?
            ORDER BY sequence_number
            """, (resultSet, rowNumber) -> new InvestigationStep(
                uuid(resultSet, "id"), uuid(resultSet, "incident_id"), resultSet.getInt("sequence_number"),
                new AgentDecision(resultSet.getString("decision_type"),
                    resultSet.getString("decision_summary"), resultSet.getString("toolset")),
                resultSet.getString("outcome"), instant(resultSet, "created_at")), incidentId);
    }

    private List<Hypothesis> findHypotheses(UUID incidentId, List<Evidence> evidence) {
        Map<UUID, Evidence> evidenceById = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            evidenceById.put(item.id(), item);
        }
        return jdbcTemplate.query("""
            SELECT id, incident_id, statement, status, confidence, created_at
            FROM stackwatch_incident.hypotheses
            WHERE incident_id = ?
            ORDER BY created_at, id
            """, (resultSet, rowNumber) -> {
            HypothesisRow row = new HypothesisRow(
                uuid(resultSet, "id"), uuid(resultSet, "incident_id"), resultSet.getString("statement"),
                Hypothesis.VerificationStatus.valueOf(resultSet.getString("status")),
                nullableDouble(resultSet, "confidence"), instant(resultSet, "created_at"));
            Hypothesis hypothesis = new Hypothesis(row.id(), row.incidentId(), row.statement(), row.confidence(),
                findCitedEvidence(row.id(), evidenceById), row.createdAt());
            if (hypothesis.verificationStatus() != row.status()) {
                throw new IllegalStateException("persisted hypothesis status violates the evidence gate");
            }
            return hypothesis;
        }, incidentId);
    }

    private List<Evidence> findCitedEvidence(UUID hypothesisId, Map<UUID, Evidence> evidenceById) {
        List<UUID> citedIds = jdbcTemplate.query("""
            SELECT evidence_id
            FROM stackwatch_incident.hypothesis_evidence
            WHERE hypothesis_id = ?
            ORDER BY evidence_id
            """, (resultSet, rowNumber) -> uuid(resultSet, "evidence_id"), hypothesisId);
        return citedIds.stream().map(evidenceById::get).map(item -> {
            if (item == null) {
                throw new IllegalStateException("hypothesis citation does not belong to its incident");
            }
            return item;
        }).toList();
    }

    private static RowMapper<IncidentRow> incidentRowMapper() {
        return (resultSet, rowNumber) -> new IncidentRow(
            uuid(resultSet, "id"), resultSet.getString("application_name"),
            resultSet.getString("environment"), resultSet.getString("cluster_id"),
            IncidentStatus.valueOf(resultSet.getString("status")), instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"), instant(resultSet, "started_at"),
            instant(resultSet, "completed_at"), resultSet.getString("failure_reason"));
    }

    private static RowMapper<Observation> observationRowMapper() {
        return (resultSet, rowNumber) -> new Observation(
            uuid(resultSet, "id"), uuid(resultSet, "incident_id"), uuid(resultSet, "step_id"),
            resultSet.getString("source_type"), resultSet.getString("status"),
            resultSet.getString("redacted_summary"), resultSet.getString("provenance"),
            instant(resultSet, "observed_from"), instant(resultSet, "observed_to"),
            resultSet.getString("content_hash"), instant(resultSet, "created_at"));
    }

    private static RowMapper<Evidence> evidenceRowMapper() {
        return (resultSet, rowNumber) -> new Evidence(
            uuid(resultSet, "id"), uuid(resultSet, "incident_id"), uuid(resultSet, "observation_id"),
            resultSet.getString("source_type"), resultSet.getString("redacted_summary"),
            resultSet.getString("provenance"), instant(resultSet, "observed_from"),
            instant(resultSet, "observed_to"), resultSet.getString("content_hash"),
            instant(resultSet, "created_at"));
    }

    private RowMapper<ReportRow> reportRowMapper() {
        return (resultSet, rowNumber) -> new ReportRow(
            uuid(resultSet, "id"), uuid(resultSet, "incident_id"), resultSet.getString("recommendation"),
            fromJson(resultSet.getString("missing_evidence")),
            IncidentStatus.valueOf(resultSet.getString("review_outcome")), instant(resultSet, "created_at"));
    }

    private String toJson(List<String> missingEvidence) {
        try {
            return objectMapper.writeValueAsString(missingEvidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("missing evidence cannot be serialized", exception);
        }
    }

    private List<String> fromJson(String missingEvidence) {
        try {
            return objectMapper.readValue(missingEvidence, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted missing evidence is invalid", exception);
        }
    }

    private static String activeKey(String applicationName, String environment, String clusterId) {
        return applicationName + "|" + environment + "|" + clusterId;
    }

    private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private record IncidentRow(UUID id, String applicationName, String environment, String clusterId,
                               IncidentStatus status, Instant createdAt, Instant updatedAt,
                               Instant startedAt, Instant completedAt, String failureReason) {

        private Incident toIncident(List<IncidentTrigger> triggers, List<InvestigationStep> steps) {
            return new Incident(id, applicationName, environment, clusterId, status, triggers, steps,
                createdAt, updatedAt, startedAt, completedAt, failureReason);
        }
    }

    private record HypothesisRow(UUID id, UUID incidentId, String statement,
                                 Hypothesis.VerificationStatus status, Double confidence, Instant createdAt) {
    }

    private record ReportRow(UUID id, UUID incidentId, String recommendation, List<String> missingEvidence,
                             IncidentStatus reviewOutcome, Instant createdAt) {
    }
}

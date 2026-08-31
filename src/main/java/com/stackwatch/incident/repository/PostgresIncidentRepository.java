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
import java.util.List;
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

    private static final String SCHEMA = "stackwatch_incident";
    private static final String STALE_FAILURE_REASON =
        "Investigation was marked stale and failed after process restart";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresIncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * PostgreSQL advisory locking serializes each active identity. The partial unique index then remains
     * the final database guard, while a transaction ensures a reused incident receives its trigger.
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

    @Override
    public void update(Incident incident) {
        int updated = jdbcTemplate.update("""
            UPDATE stackwatch_incident.incidents
            SET status = ?, updated_at = ?, started_at = ?, completed_at = ?, failure_reason = ?
            WHERE id = ?
            """, incident.status().name(), incident.updatedAt(), incident.startedAt(),
            incident.completedAt(), incident.failureReason(), incident.id());
        if (updated != 1) {
            throw new IllegalArgumentException("incident does not exist: " + incident.id());
        }
    }

    @Override
    public void appendStep(InvestigationStep step) {
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.steps
                (id, incident_id, sequence_number, decision_type, decision_summary, toolset, outcome,
                 created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, step.id(), step.incidentId(), step.sequenceNumber(), step.decision().decisionType(),
            step.decision().summary(), step.decision().toolset(), step.outcome(), step.createdAt());
    }

    @Override
    public void appendObservation(Observation observation) {
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.observations
                (id, incident_id, step_id, source_type, status, redacted_summary, provenance,
                 observed_from, observed_to, content_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, observation.id(), observation.incidentId(), observation.stepId(), observation.sourceType(),
            observation.status(), observation.redactedSummary(), observation.provenance(),
            observation.observedFrom(), observation.observedTo(), observation.contentHash(),
            observation.createdAt());
    }

    @Override
    public void appendEvidence(Evidence evidence) {
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.evidence
                (id, incident_id, observation_id, source_type, redacted_summary, provenance,
                 observed_from, observed_to, content_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, evidence.id(), evidence.incidentId(), evidence.observationId(), evidence.sourceType(),
            evidence.redactedSummary(), evidence.provenance(), evidence.observedFrom(), evidence.observedTo(),
            evidence.contentHash(), evidence.createdAt());
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
        jdbcTemplate.update("DELETE FROM stackwatch_incident.hypotheses WHERE incident_id = ?", report.incidentId());
        for (Hypothesis hypothesis : report.hypotheses()) {
            jdbcTemplate.update("""
                INSERT INTO stackwatch_incident.hypotheses
                    (id, incident_id, statement, status, confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, hypothesis.id(), hypothesis.incidentId(), hypothesis.statement(),
                hypothesis.verificationStatus().name(), hypothesis.confidence(), hypothesis.createdAt());
        }
        jdbcTemplate.update("""
            INSERT INTO stackwatch_incident.reports
                (id, incident_id, recommendation, missing_evidence, review_outcome, created_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (incident_id) DO UPDATE
            SET recommendation = EXCLUDED.recommendation,
                missing_evidence = EXCLUDED.missing_evidence,
                review_outcome = EXCLUDED.review_outcome,
                created_at = EXCLUDED.created_at
            """, report.id(), report.incidentId(), report.recommendation(),
            toJson(report.missingEvidence()), report.reviewOutcome().name(), report.createdAt());
    }

    @Override
    public Optional<IncidentReport> findReport(UUID incidentId) {
        List<IncidentReport> reports = jdbcTemplate.query("""
            SELECT id, incident_id, recommendation, missing_evidence, review_outcome, created_at
            FROM stackwatch_incident.reports
            WHERE incident_id = ?
            """, (resultSet, rowNumber) -> new IncidentReport(
                uuid(resultSet, "id"), uuid(resultSet, "incident_id"), findHypotheses(incidentId),
                findEvidence(incidentId), fromJson(resultSet.getString("missing_evidence")),
                resultSet.getString("recommendation"),
                IncidentStatus.valueOf(resultSet.getString("review_outcome")),
                instant(resultSet, "created_at")), incidentId);
        return reports.stream().findFirst();
    }

    @Override
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

    private List<Hypothesis> findHypotheses(UUID incidentId) {
        return jdbcTemplate.query("""
            SELECT id, incident_id, statement, status, confidence, created_at
            FROM stackwatch_incident.hypotheses
            WHERE incident_id = ?
            ORDER BY created_at, id
            """, (resultSet, rowNumber) -> new Hypothesis(
                uuid(resultSet, "id"), uuid(resultSet, "incident_id"), resultSet.getString("statement"),
                Hypothesis.VerificationStatus.valueOf(resultSet.getString("status")),
                nullableDouble(resultSet, "confidence"), instant(resultSet, "created_at")), incidentId);
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
}

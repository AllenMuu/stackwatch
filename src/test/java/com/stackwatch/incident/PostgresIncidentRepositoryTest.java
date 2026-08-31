package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.stackwatch.incident.repository.PostgresIncidentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresIncidentRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private PostgresIncidentRepository repository;

    @BeforeEach
    void setUp() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stackwatch_incident")
            .createSchemas(true)
            .load()
            .migrate();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new PostgresIncidentRepository(jdbcTemplate, new ObjectMapper());
        jdbcTemplate.execute("TRUNCATE stackwatch_incident.incidents CASCADE");
    }

    @Test
    void createsThenReusesActiveIncidentAndAppendsItsTrigger() {
        Incident created = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "first signal", CREATED_AT));
        Incident reused = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("MANUAL", "investigate", CREATED_AT.plusSeconds(1)));

        assertThat(reused.id()).isEqualTo(created.id());
        assertThat(reused.triggers()).extracting(IncidentTrigger::triggerType)
            .containsExactly("FAST_PATH", "MANUAL");
        assertThat(repository.findById(created.id())).contains(reused);
    }

    @Test
    void persistsAuditRecordsAndRetrievesStructuredReport() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        Incident running = incident.start(CREATED_AT.plusSeconds(1));
        repository.update(running);

        InvestigationStep step = new InvestigationStep(
            UUID.randomUUID(), incident.id(), 1,
            new AgentDecision("CALL_TOOL", "Inspect downstream trace", "TRACE"),
            "SUCCESS", CREATED_AT.plusSeconds(2));
        repository.appendStep(step);
        Observation observation = new Observation(
            UUID.randomUUID(), incident.id(), step.id(), "TRACE", "SUCCESS", "timeout span",
            "trace://orders/123", CREATED_AT, CREATED_AT.plusSeconds(2), "observation-hash",
            CREATED_AT.plusSeconds(2));
        repository.appendObservation(observation);
        Evidence evidence = new Evidence(
            UUID.randomUUID(), incident.id(), observation.id(), "TRACE", "timeout span",
            "trace://orders/123", CREATED_AT, CREATED_AT.plusSeconds(2), "evidence-hash",
            CREATED_AT.plusSeconds(2));
        repository.appendEvidence(evidence);
        Hypothesis hypothesis = Hypothesis.assess(
            UUID.randomUUID(), incident.id(), "Downstream order service timed out", 0.7,
            List.of(evidence), CREATED_AT.plusSeconds(3));
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), incident.id(), List.of(hypothesis), List.of(evidence),
            List.of("Deployment correlation"), "Inspect the downstream deployment", CREATED_AT.plusSeconds(4));
        repository.saveReport(report);

        assertThat(repository.findById(incident.id()).orElseThrow().steps()).containsExactly(step);
        assertThat(repository.findObservations(incident.id())).containsExactly(observation);
        assertThat(repository.findEvidence(incident.id())).containsExactly(evidence);
        assertThat(repository.findReport(incident.id())).contains(report);
    }

    @Test
    void marksStaleRunningIncidentsAsFailedWithoutDeletingTheirAuditHistory() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        repository.update(incident.start(CREATED_AT.plusSeconds(1)));

        int marked = repository.markStaleRunningFailed(CREATED_AT.plusSeconds(2), CREATED_AT.plusSeconds(3));

        Incident failed = repository.findById(incident.id()).orElseThrow();
        assertThat(marked).isEqualTo(1);
        assertThat(failed.status()).isEqualTo(IncidentStatus.FAILED);
        assertThat(failed.failureReason()).contains("stale");
    }

    private static IncidentTrigger trigger(String type, String note, Instant createdAt) {
        return new IncidentTrigger(UUID.randomUUID(), type, note, createdAt);
    }
}

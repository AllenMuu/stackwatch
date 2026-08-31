package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresIncidentRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private PostgresIncidentRepository repository;

    private JdbcTemplate jdbcTemplate;

    private AnnotationConfigApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = new AnnotationConfigApplicationContext(RepositoryTestConfiguration.class);
        repository = applicationContext.getBean(PostgresIncidentRepository.class);
        jdbcTemplate = applicationContext.getBean(JdbcTemplate.class);
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stackwatch_incident")
            .createSchemas(true)
            .load()
            .migrate();
        jdbcTemplate.execute("TRUNCATE stackwatch_incident.incidents CASCADE");
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
    }

    @Test
    void usesSpringProxyToCreateThenReuseAnActiveIncidentAndTouchItsActivityTime() {
        Incident created = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "first signal", CREATED_AT));
        Incident reused = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("MANUAL", "investigate", CREATED_AT.plusSeconds(1)));

        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(reused.id()).isEqualTo(created.id());
        assertThat(reused.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThat(reused.triggers()).extracting(IncidentTrigger::triggerType)
            .containsExactly("FAST_PATH", "MANUAL");
    }

    @Test
    @Timeout(10)
    void concurrentCreateOrReuseProducesOneActiveIncidentWithBothTriggers() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Incident> first = executor.submit(() -> createAfterBarrier(ready, start, "FAST_PATH"));
            Future<Incident> second = executor.submit(() -> createAfterBarrier(ready, start, "MANUAL"));

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Incident firstResult = first.get(5, TimeUnit.SECONDS);
            Incident secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.id()).isEqualTo(secondResult.id());
            assertThat(repository.findById(firstResult.id()).orElseThrow().triggers()).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startsOnceWithCompareAndSetAndDoesNotResurrectATerminalIncident() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));

        Incident running = repository.startIfPending(incident.id(), CREATED_AT.plusSeconds(1)).orElseThrow();
        Incident completed = running.complete(CREATED_AT.plusSeconds(2));

        assertThat(repository.startIfPending(incident.id(), CREATED_AT.plusSeconds(2))).isEmpty();
        assertThat(repository.updateIfCurrentStatus(completed, IncidentStatus.RUNNING)).isTrue();
        assertThat(repository.updateIfCurrentStatus(running, IncidentStatus.PENDING)).isFalse();
        assertThat(repository.findById(incident.id()).orElseThrow().status()).isEqualTo(IncidentStatus.COMPLETED);
    }

    @Test
    @Timeout(10)
    void concurrentStartIfPendingAllowsExactlyOneWorkerToClaimTheIncident() throws Exception {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Incident>> first = executor.submit(
                () -> startAfterBarrier(incident.id(), ready, start));
            Future<Optional<Incident>> second = executor.submit(
                () -> startAfterBarrier(incident.id(), ready, start));

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int claims = (first.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                + (second.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claims).isEqualTo(1);
            assertThat(repository.findById(incident.id()).orElseThrow().status())
                .isEqualTo(IncidentStatus.RUNNING);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistsCitedEvidenceLinksAndRetrievesTheStructuredReport() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        Incident running = repository.startIfPending(incident.id(), CREATED_AT.plusSeconds(1)).orElseThrow();
        InvestigationStep step = step(running.id(), CREATED_AT.plusSeconds(2));
        repository.appendStep(step);
        Evidence logs = persistedEvidence(incident.id(), step.id(), "LOGS", CREATED_AT.plusSeconds(3));
        Evidence trace = persistedEvidence(incident.id(), step.id(), "TRACE", CREATED_AT.plusSeconds(4));
        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), incident.id(), "Downstream order service timed out", 0.9,
            List.of(logs, trace), CREATED_AT.plusSeconds(5));
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), incident.id(), List.of(hypothesis), List.of(logs, trace), List.of(),
            "Inspect the downstream deployment", CREATED_AT.plusSeconds(6));

        repository.saveReport(report);

        IncidentReport loaded = repository.findReport(incident.id()).orElseThrow();
        assertThat(loaded.reviewOutcome()).isEqualTo(IncidentStatus.COMPLETED);
        assertThat(loaded.hypotheses()).singleElement().satisfies(saved -> {
            assertThat(saved.verificationStatus()).isEqualTo(Hypothesis.VerificationStatus.VERIFIED);
            assertThat(saved.citedEvidence()).containsExactlyInAnyOrder(logs, trace);
        });

        Evidence deployment = persistedEvidence(
            incident.id(), step.id(), "DEPLOYMENT", CREATED_AT.plusSeconds(7));
        IncidentReport historical = repository.findReport(incident.id()).orElseThrow();

        assertThat(historical.evidence()).containsExactlyInAnyOrder(logs, trace);
        assertThat(historical.evidence()).doesNotContain(deployment);
        assertThat(historical.hypotheses()).singleElement().satisfies(saved ->
            assertThat(saved.citedEvidence()).containsExactlyInAnyOrder(logs, trace));
    }

    @Test
    void rejectsReportEvidenceThatDoesNotMatchThePersistedEvidenceRecord() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        Incident running = repository.startIfPending(incident.id(), CREATED_AT.plusSeconds(1)).orElseThrow();
        InvestigationStep step = step(running.id(), CREATED_AT.plusSeconds(2));
        repository.appendStep(step);
        Evidence logs = persistedEvidence(incident.id(), step.id(), "LOGS", CREATED_AT.plusSeconds(3));
        Evidence trace = persistedEvidence(incident.id(), step.id(), "TRACE", CREATED_AT.plusSeconds(4));
        Evidence callerSuppliedLogs = new Evidence(
            logs.id(), logs.incidentId(), logs.observationId(), "DEPLOYMENT", logs.redactedSummary(),
            logs.provenance(), logs.observedFrom(), logs.observedTo(), logs.contentHash(), logs.createdAt());
        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), incident.id(), "Downstream order service timed out", 0.9,
            List.of(callerSuppliedLogs, trace), CREATED_AT.plusSeconds(5));
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), incident.id(), List.of(hypothesis), List.of(callerSuppliedLogs, trace), List.of(),
            "Inspect the downstream deployment", CREATED_AT.plusSeconds(6));

        assertThatIllegalArgumentException().isThrownBy(() -> repository.saveReport(report));
        assertThat(repository.findReport(incident.id())).isEmpty();
    }

    @Test
    void rejectsEvidenceForAnotherIncidentsObservationAndUnpersistedReportEvidence() {
        Incident first = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "first", CREATED_AT));
        Incident second = repository.createOrReuse(
            "orders", "prod", "cluster-43", trigger("FAST_PATH", "second", CREATED_AT));
        Observation observation = new Observation(
            UUID.randomUUID(), first.id(), null, "LOGS", "SUCCESS", "timeout started",
            "logs://orders", CREATED_AT, CREATED_AT, "observation-hash", CREATED_AT);
        repository.appendObservation(observation);
        Evidence foreignEvidence = new Evidence(
            UUID.randomUUID(), second.id(), observation.id(), "LOGS", "timeout started",
            "logs://orders", CREATED_AT, CREATED_AT, "evidence-hash", CREATED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> repository.appendEvidence(foreignEvidence));

        Evidence unpersistedLogs = evidence(first.id(), UUID.randomUUID(), "LOGS", CREATED_AT.plusSeconds(1));
        Evidence unpersistedTrace = evidence(first.id(), UUID.randomUUID(), "TRACE", CREATED_AT.plusSeconds(2));
        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), first.id(), "Downstream timeout", 0.9,
            List.of(unpersistedLogs, unpersistedTrace), CREATED_AT.plusSeconds(3));
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), first.id(), List.of(hypothesis), List.of(unpersistedLogs, unpersistedTrace),
            List.of(), "Inspect downstream", CREATED_AT.plusSeconds(4));

        assertThatIllegalArgumentException().isThrownBy(() -> repository.saveReport(report));
    }

    @Test
    void activeStepPreventsStaleFailureUntilItsActivityBecomesOld() {
        Incident incident = repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger("FAST_PATH", "qualifying", CREATED_AT));
        Incident running = repository.startIfPending(incident.id(), CREATED_AT.plusSeconds(1)).orElseThrow();
        repository.appendStep(step(running.id(), CREATED_AT.plusSeconds(5)));

        assertThat(repository.markStaleRunningFailed(CREATED_AT.plusSeconds(3), CREATED_AT.plusSeconds(6)))
            .isZero();
        assertThat(repository.markStaleRunningFailed(CREATED_AT.plusSeconds(6), CREATED_AT.plusSeconds(7)))
            .isEqualTo(1);
        assertThat(repository.findById(incident.id()).orElseThrow().status()).isEqualTo(IncidentStatus.FAILED);
    }

    private Incident createAfterBarrier(CountDownLatch ready, CountDownLatch start, String triggerType)
        throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent create barrier timed out");
        }
        return repository.createOrReuse(
            "orders", "prod", "cluster-42", trigger(triggerType, triggerType, CREATED_AT));
    }

    private Optional<Incident> startAfterBarrier(UUID incidentId, CountDownLatch ready, CountDownLatch start)
        throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent start barrier timed out");
        }
        return repository.startIfPending(incidentId, CREATED_AT.plusSeconds(1));
    }

    private Evidence persistedEvidence(UUID incidentId, UUID stepId, String sourceType, Instant createdAt) {
        Observation observation = new Observation(
            UUID.randomUUID(), incidentId, stepId, sourceType, "SUCCESS", sourceType + " timeout",
            sourceType.toLowerCase() + "://orders", CREATED_AT, createdAt, sourceType + "-observation",
            createdAt);
        repository.appendObservation(observation);
        Evidence evidence = evidence(incidentId, observation.id(), sourceType, createdAt);
        repository.appendEvidence(evidence);
        return evidence;
    }

    private static InvestigationStep step(UUID incidentId, Instant createdAt) {
        return new InvestigationStep(
            UUID.randomUUID(), incidentId, 1,
            new AgentDecision("CALL_TOOL", "Inspect downstream trace", "TRACE"),
            "SUCCESS", createdAt);
    }

    private static IncidentTrigger trigger(String type, String note, Instant createdAt) {
        return new IncidentTrigger(UUID.randomUUID(), type, note, createdAt);
    }

    private static Evidence evidence(UUID incidentId, UUID observationId, String sourceType, Instant createdAt) {
        return new Evidence(
            UUID.randomUUID(), incidentId, observationId, sourceType, sourceType + " timeout",
            sourceType.toLowerCase() + "://orders", CREATED_AT, createdAt, sourceType + "-hash", createdAt);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RepositoryTestConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PostgresIncidentRepository incidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
            return new PostgresIncidentRepository(jdbcTemplate, objectMapper);
        }
    }
}

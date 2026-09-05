package com.stackwatch.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwatch.domain.FingerprintVersion;
import com.stackwatch.domain.RootCauseAnalysis;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class PostgresErrorGroupRepositoryTest {

    private static final Instant EARLY = Instant.parse("2026-08-31T01:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-31T03:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private AnnotationConfigApplicationContext applicationContext;
    private PostgresErrorGroupRepository repository;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        applicationContext = new AnnotationConfigApplicationContext(RepositoryTestConfiguration.class);
        repository = applicationContext.getBean(PostgresErrorGroupRepository.class);
        jdbcTemplate = applicationContext.getBean(JdbcTemplate.class);
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stackwatch_error_history")
            .createSchemas(true)
            .locations("classpath:db/error-history")
            .load()
            .migrate();
        jdbcTemplate.execute("TRUNCATE stackwatch_error_history.error_groups CASCADE");
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
    }

    @Test
    void migratesTheErrorHistorySchema() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('stackwatch_error_history.error_groups')", String.class))
            .isEqualTo("stackwatch_error_history.error_groups");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('stackwatch_error_history.accepted_error_events')", String.class))
            .isEqualTo("stackwatch_error_history.accepted_error_events");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'stackwatch_error_history' "
                + "AND table_name = 'accepted_error_events' AND column_name = 'event_id'",
            String.class)).isEqualTo("NO");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'stackwatch_error_history' "
                + "AND table_name = 'error_groups' AND column_name = 'loose_fingerprint'",
            String.class)).isEqualTo("NO");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = 'stackwatch_error_history' "
                + "AND table_name = 'error_groups' AND column_name = 'cluster_id'",
            String.class)).isEqualTo("character varying");
    }

    @Test
    void createsGroupCountsOccurrencesAndRoundTripsJson() {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "create", true);

        RecordOccurrenceResult result = repository.record(
            new RecordOccurrenceCommand(group, "event-1", LATE));

        assertThat(result.accepted()).isTrue();
        assertThat(result.group().id()).isEqualTo(group.id());
        assertThat(result.group().occurrenceCount()).isEqualTo(1);
        assertThat(result.group().firstSeen()).isEqualTo(LATE);
        assertThat(result.group().lastSeen()).isEqualTo(LATE);
        assertThat(result.group().analysis().rootCause()).isEqualTo("database timeout");
        assertThat(result.group().normalizedFrames()).containsExactly("app.OrderService.load");
    }

    @Test
    void suppressesDuplicateEventForSameApplication() {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "duplicate", false);
        RecordOccurrenceCommand command = new RecordOccurrenceCommand(group, "event-1", LATE);

        RecordOccurrenceResult first = repository.record(command);
        RecordOccurrenceResult duplicate = repository.record(
            new RecordOccurrenceCommand(group, "event-1", EARLY));

        assertThat(first.accepted()).isTrue();
        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.group().occurrenceCount()).isEqualTo(1);
        assertThat(duplicate.group().firstSeen()).isEqualTo(LATE);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM stackwatch_error_history.accepted_error_events", Long.class))
            .isEqualTo(1L);
    }

    @Test
    void countsEveryOccurrenceWhenEventIdIsMissing() {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "without-event-id", false);

        RecordOccurrenceResult first = repository.record(
            new RecordOccurrenceCommand(group, null, LATE));
        RecordOccurrenceResult second = repository.record(
            new RecordOccurrenceCommand(group, "   ", EARLY));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(second.group().occurrenceCount()).isEqualTo(2);
        assertThat(second.group().firstSeen()).isEqualTo(EARLY);
        assertThat(second.group().lastSeen()).isEqualTo(LATE);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM stackwatch_error_history.accepted_error_events", Long.class))
            .isEqualTo(0L);
    }

    @Test
    void keepsOutOfOrderTimesAtMinimumAndMaximum() {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "out-of-order", false);

        repository.record(new RecordOccurrenceCommand(group, "later", LATE));
        RecordOccurrenceResult result = repository.record(
            new RecordOccurrenceCommand(group, "earlier", EARLY));

        assertThat(result.group().occurrenceCount()).isEqualTo(2);
        assertThat(result.group().firstSeen()).isEqualTo(EARLY);
        assertThat(result.group().lastSeen()).isEqualTo(LATE);
    }

    @Test
    void isolatesApplicationAndFingerprintVersion() {
        ErrorGroup ordersV2 = group("orders", FingerprintVersion.V2, "same-text", false);
        ErrorGroup billingV2 = group("billing", FingerprintVersion.V2, "same-text", false);
        ErrorGroup ordersV1 = group("orders", FingerprintVersion.V1, "same-text", false);

        repository.record(new RecordOccurrenceCommand(ordersV2, "orders-v2", EARLY));
        repository.record(new RecordOccurrenceCommand(billingV2, "billing-v2", EARLY));
        repository.record(new RecordOccurrenceCommand(ordersV1, "orders-v1", EARLY));

        assertThat(repository.findExact(ordersV2.key())).get().extracting(ErrorGroup::occurrenceCount)
            .isEqualTo(1L);
        assertThat(repository.findExact(billingV2.key())).get().extracting(ErrorGroup::occurrenceCount)
            .isEqualTo(1L);
        assertThat(repository.findExact(ordersV1.key())).get().extracting(ErrorGroup::occurrenceCount)
            .isEqualTo(1L);
    }

    @Test
    void strictFingerprintIsTheFinalGroupUniquenessBoundary() {
        ErrorGroup first = group("orders", FingerprintVersion.V2, "first", false);
        ErrorGroup sameIdentity = group("orders", FingerprintVersion.V2, "second", false);
        ErrorGroupKey sameKey = new ErrorGroupKey("orders", FingerprintVersion.V2,
            first.key().strictFingerprint());
        sameIdentity = new ErrorGroup(new ErrorGroup.GroupIdentity(sameIdentity.id(), sameKey),
            sameIdentity.facts(), sameIdentity.lifecycle(), sameIdentity.analysis(),
            sameIdentity.clusterId());

        RecordOccurrenceResult firstResult = repository.record(
            new RecordOccurrenceCommand(first, "first-event", EARLY));
        RecordOccurrenceResult secondResult = repository.record(
            new RecordOccurrenceCommand(sameIdentity, "second-event", LATE));

        assertThat(secondResult.group().id()).isEqualTo(firstResult.group().id());
        assertThat(secondResult.group().occurrenceCount()).isEqualTo(2);
        assertThat(repository.findExact(sameKey)).get().extracting(ErrorGroup::looseFingerprint)
            .isEqualTo(first.looseFingerprint());
    }

    @Test
    void serializesConcurrentCreationAndDistinctEventsForOneIdentity() throws Exception {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "concurrent", false);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<RecordOccurrenceResult>> futures = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> executor.submit(() -> repository.record(
                    new RecordOccurrenceCommand(group, "concurrent-" + index, LATE))))
                .toList();

            for (Future<RecordOccurrenceResult> future : futures) {
                RecordOccurrenceResult result = future.get();
                assertThat(result.accepted()).isTrue();
                assertThat(result.group().id()).isEqualTo(group.id());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.findExact(group.key())).get()
            .extracting(ErrorGroup::occurrenceCount).isEqualTo(12L);
    }

    @Test
    void looksUpGroupThroughNewRepositoryInstance() {
        ErrorGroup group = group("orders", FingerprintVersion.V2, "restart", false);
        repository.record(new RecordOccurrenceCommand(group, "event-1", EARLY));

        PostgresErrorGroupRepository restarted = new PostgresErrorGroupRepository(jdbcTemplate, objectMapper);

        Optional<ErrorGroup> loaded = restarted.findExact(group.key());
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().id()).isEqualTo(group.id());
        assertThat(loaded.orElseThrow().occurrenceCount()).isEqualTo(1);
    }

    private static ErrorGroup group(String appName, FingerprintVersion version, String token,
                                    boolean withAnalysis) {
        ErrorGroupKey key = new ErrorGroupKey(appName, version, hash("strict-" + token));
        RootCauseAnalysis analysis = withAnalysis
            ? new RootCauseAnalysis("database timeout", "DATABASE", "HIGH", 0.95,
                "retry", List.of("connection pool exhausted"), false)
            : null;
        return ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(), key),
            new ErrorGroup.GroupFacts(hash("loose-" + token), "OuterException", "CauseException",
                "failed to load order", List.of("app.OrderService.load")),
            analysis, "cluster-" + token));
    }

    private static String hash(String value) {
        return (value + "0".repeat(64)).substring(0, 64);
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
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean(name = {"transactionManager", "errorHistoryTransactionManager"})
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PostgresErrorGroupRepository errorGroupRepository(JdbcTemplate jdbcTemplate,
                                                          ObjectMapper objectMapper) {
            return new PostgresErrorGroupRepository(jdbcTemplate, objectMapper);
        }
    }
}

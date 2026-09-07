package com.stackwatch.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwatch.domain.FingerprintVersion;
import com.stackwatch.domain.RootCauseAnalysis;
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
import org.springframework.beans.factory.annotation.Qualifier;

/** PostgreSQL-backed, idempotent error-group occurrence repository. */
@Repository
@ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled", havingValue = "true")
public class PostgresErrorGroupRepository implements ErrorGroupRepository {

    private static final String GROUP_COLUMNS = "id, app_name, fingerprint_version, "
        + "strict_fingerprint, loose_fingerprint, outer_exception_type, "
        + "effective_exception_type, message_template, normalized_frames::text, rca::text, "
        + "cluster_id, occurrence_count, first_seen, last_seen, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresErrorGroupRepository(@Qualifier("errorHistoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ErrorGroup> findExact(ErrorGroupKey key) {
        return jdbcTemplate.query("""
            SELECT %s
            FROM stackwatch_error_history.error_groups
            WHERE app_name = ? AND fingerprint_version = ? AND strict_fingerprint = ?
            """.formatted(GROUP_COLUMNS), groupRowMapper(), key.appName(),
            key.fingerprintVersion().name(), key.strictFingerprint()).stream().findFirst();
    }

    /** Loads a group by its durable identifier for transaction and restart checks. */
    public Optional<ErrorGroup> findById(UUID groupId) {
        return jdbcTemplate.query("""
            SELECT %s
            FROM stackwatch_error_history.error_groups
            WHERE id = ?
            """.formatted(GROUP_COLUMNS), groupRowMapper(), groupId).stream().findFirst();
    }

    /**
     * Records group creation and occurrence acceptance in one transaction. A transaction-scoped
     * advisory lock serializes creation of the same composite identity before the unique constraint
     * is checked, while the event primary key provides durable idempotency.
     */
    @Override
    @Transactional(transactionManager = "errorHistoryTransactionManager")
    public RecordOccurrenceResult record(RecordOccurrenceCommand command) {
        Instant occurredAt = command.occurredAt() == null ? Instant.now() : command.occurredAt();
        if (hasEventId(command.eventId())) {
            String appName = command.group().key().appName();
            lockEvent(appName, command.eventId());
            Optional<UUID> acceptedGroupId = findAcceptedEventGroupId(appName, command.eventId());
            if (acceptedGroupId.isPresent()) {
                return new RecordOccurrenceResult(findById(acceptedGroupId.orElseThrow()).orElseThrow(), false);
            }
        }
        ErrorGroup group = findOrCreate(command.group(), occurredAt);
        if (!hasEventId(command.eventId())) {
            incrementWithMinMax(group.id(), occurredAt);
            return new RecordOccurrenceResult(findById(group.id()).orElseThrow(), true);
        }

        int inserted = insertEventIfAbsent(command.group().key().appName(), command.eventId(),
            group.id(), occurredAt);
        if (inserted == 0) {
            UUID existingGroupId = findAcceptedEventGroupId(
                command.group().key().appName(), command.eventId()).orElseThrow();
            return new RecordOccurrenceResult(findById(existingGroupId).orElseThrow(), false);
        }

        incrementWithMinMax(group.id(), occurredAt);
        return new RecordOccurrenceResult(findById(group.id()).orElseThrow(), true);
    }

    private ErrorGroup findOrCreate(ErrorGroup requested, Instant occurredAt) {
        ErrorGroupKey key = requested.key();
        lockIdentity(key);
        Optional<ErrorGroup> existing = findExact(key);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        Instant createdAt = requested.createdAt() == null ? Instant.now() : requested.createdAt();
        jdbcTemplate.update("""
            INSERT INTO stackwatch_error_history.error_groups
                (id, app_name, fingerprint_version, strict_fingerprint, loose_fingerprint,
                 outer_exception_type, effective_exception_type, message_template,
                 normalized_frames, rca, cluster_id, occurrence_count, first_seen, last_seen,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, 0, ?, ?, ?, ?)
            ON CONFLICT (app_name, fingerprint_version, strict_fingerprint) DO NOTHING
            """, requested.id(), key.appName(), key.fingerprintVersion().name(), key.strictFingerprint(),
            requested.looseFingerprint(), requested.outerExceptionType(), requested.effectiveExceptionType(),
            requested.messageTemplate(), toJson(requested.normalizedFrames()), toJson(requested.analysis()),
            requested.clusterId(), occurredAt, occurredAt, createdAt, createdAt);
        return findExact(key).orElseThrow(
            () -> new IllegalStateException("error group was not created: " + key));
    }

    private void lockIdentity(ErrorGroupKey key) {
        String lockKey = key.appName() + "\u0000" + key.fingerprintVersion().name()
            + "\u0000" + key.strictFingerprint();
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
            (resultSet, rowNumber) -> Boolean.TRUE, lockKey);
    }

    private void lockEvent(String appName, String eventId) {
        String lockKey = "event\u0000" + appName + "\u0000" + eventId;
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
            (resultSet, rowNumber) -> Boolean.TRUE, lockKey);
    }

    private int insertEventIfAbsent(String appName, String eventId, UUID groupId, Instant occurredAt) {
        return jdbcTemplate.update("""
            INSERT INTO stackwatch_error_history.accepted_error_events
                (app_name, event_id, group_id, accepted_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (app_name, event_id) DO NOTHING
            """, appName, eventId, groupId, occurredAt);
    }

    private Optional<UUID> findAcceptedEventGroupId(String appName, String eventId) {
        return jdbcTemplate.query("""
            SELECT group_id
            FROM stackwatch_error_history.accepted_error_events
            WHERE app_name = ? AND event_id = ?
            """, (resultSet, rowNumber) -> resultSet.getObject("group_id", UUID.class), appName,
            eventId).stream().findFirst();
    }

    private void incrementWithMinMax(UUID groupId, Instant occurredAt) {
        int updated = jdbcTemplate.update("""
            UPDATE stackwatch_error_history.error_groups
            SET occurrence_count = occurrence_count + 1,
                first_seen = LEAST(first_seen, ?),
                last_seen = GREATEST(last_seen, ?),
                updated_at = GREATEST(updated_at, ?)
            WHERE id = ?
            """, occurredAt, occurredAt, Instant.now(), groupId);
        if (updated != 1) {
            throw new IllegalStateException("error group does not exist: " + groupId);
        }
    }

    private RowMapper<ErrorGroup> groupRowMapper() {
        return (resultSet, rowNumber) -> {
            ErrorGroupKey key = new ErrorGroupKey(resultSet.getString("app_name"),
                FingerprintVersion.valueOf(resultSet.getString("fingerprint_version")),
                resultSet.getString("strict_fingerprint"));
            ErrorGroup.GroupIdentity identity = new ErrorGroup.GroupIdentity(
                resultSet.getObject("id", UUID.class), key);
            ErrorGroup.GroupFacts facts = new ErrorGroup.GroupFacts(
                resultSet.getString("loose_fingerprint"), resultSet.getString("outer_exception_type"),
                resultSet.getString("effective_exception_type"), resultSet.getString("message_template"),
                fromJson(resultSet.getString("normalized_frames"), new TypeReference<>() {}));
            ErrorGroup.GroupLifecycle lifecycle = new ErrorGroup.GroupLifecycle(
                resultSet.getLong("occurrence_count"), timestamp(resultSet, "first_seen"),
                timestamp(resultSet, "last_seen"), timestamp(resultSet, "created_at"),
                timestamp(resultSet, "updated_at"));
            return new ErrorGroup(identity, facts, lifecycle,
                fromJson(resultSet.getString("rca"), RootCauseAnalysis.class),
                resultSet.getString("cluster_id"));
        };
    }

    private Instant timestamp(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid JSON in error history column", exception);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid JSON in error history column", exception);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize error history JSON", exception);
        }
    }

    private boolean hasEventId(String eventId) {
        return eventId != null && !eventId.isBlank();
    }
}

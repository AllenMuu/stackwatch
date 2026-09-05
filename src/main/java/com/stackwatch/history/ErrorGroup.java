package com.stackwatch.history;

import com.stackwatch.domain.RootCauseAnalysis;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable durable grouping projection and its RCA. */
public record ErrorGroup(UUID id, ErrorGroupKey key, String looseFingerprint,
                         String outerExceptionType, String effectiveExceptionType,
                         String messageTemplate, List<String> normalizedFrames,
                         RootCauseAnalysis analysis, String clusterId, long occurrenceCount,
                         Instant firstSeen, Instant lastSeen, Instant createdAt, Instant updatedAt) {
    public ErrorGroup {
        if (id == null || key == null) throw new IllegalArgumentException("id and key are required");
        if (occurrenceCount < 0) throw new IllegalArgumentException("occurrenceCount must not be negative");
        normalizedFrames = normalizedFrames == null ? List.of() : List.copyOf(normalizedFrames);
    }

    public static ErrorGroup newGroup(UUID id, ErrorGroupKey key, String looseFingerprint,
                                      String outerType, String effectiveType, String messageTemplate,
                                      RootCauseAnalysis analysis) {
        Instant now = Instant.now();
        return new ErrorGroup(id, key, looseFingerprint, outerType, effectiveType, messageTemplate,
            List.of(), analysis, null, 0, now, now, now, now);
    }

    public ErrorGroup acceptedAt(Instant occurredAt) {
        Instant time = occurredAt == null ? Instant.now() : occurredAt;
        Instant first = firstSeen == null || time.isBefore(firstSeen) ? time : firstSeen;
        Instant last = lastSeen == null || time.isAfter(lastSeen) ? time : lastSeen;
        return new ErrorGroup(id, key, looseFingerprint, outerExceptionType, effectiveExceptionType,
            messageTemplate, normalizedFrames, analysis, clusterId, occurrenceCount + 1,
            first, last, createdAt, Instant.now());
    }
}

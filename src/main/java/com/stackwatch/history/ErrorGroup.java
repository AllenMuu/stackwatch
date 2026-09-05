package com.stackwatch.history;

import com.stackwatch.domain.RootCauseAnalysis;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable durable grouping projection and its RCA. */
public record ErrorGroup(GroupIdentity identity, GroupFacts facts, GroupLifecycle lifecycle,
                         RootCauseAnalysis analysis, String clusterId) {
    public ErrorGroup {
        if (identity == null || facts == null || lifecycle == null) {
            throw new IllegalArgumentException("identity, facts and lifecycle are required");
        }
    }
    public UUID id() { return identity.id(); }
    public ErrorGroupKey key() { return identity.key(); }
    public String looseFingerprint() { return facts.looseFingerprint(); }
    public String outerExceptionType() { return facts.outerExceptionType(); }
    public String effectiveExceptionType() { return facts.effectiveExceptionType(); }
    public String messageTemplate() { return facts.messageTemplate(); }
    public List<String> normalizedFrames() { return facts.normalizedFrames(); }
    public long occurrenceCount() { return lifecycle.occurrenceCount(); }
    public Instant firstSeen() { return lifecycle.firstSeen(); }
    public Instant lastSeen() { return lifecycle.lastSeen(); }
    public Instant createdAt() { return lifecycle.createdAt(); }
    public Instant updatedAt() { return lifecycle.updatedAt(); }

    public static ErrorGroup newGroup(ErrorGroupSeed seed) {
        if (seed == null) throw new IllegalArgumentException("seed is required");
        Instant now = Instant.now();
        return new ErrorGroup(seed.identity(), seed.facts(),
            new GroupLifecycle(0, null, null, now, now), seed.analysis(), seed.clusterId());
    }

    public ErrorGroup acceptedAt(Instant occurredAt) {
        Instant time = occurredAt == null ? Instant.now() : occurredAt;
        Instant first = firstSeen() == null || time.isBefore(firstSeen()) ? time : firstSeen();
        Instant last = lastSeen() == null || time.isAfter(lastSeen()) ? time : lastSeen();
        return new ErrorGroup(identity, facts,
            new GroupLifecycle(occurrenceCount() + 1, first, last, createdAt(), Instant.now()),
            analysis, clusterId);
    }

    public record GroupIdentity(UUID id, ErrorGroupKey key) {
        public GroupIdentity {
            if (id == null || key == null) throw new IllegalArgumentException("id and key are required");
        }
    }
    public record GroupFacts(String looseFingerprint, String outerExceptionType,
                             String effectiveExceptionType, String messageTemplate,
                             List<String> normalizedFrames) {
        public GroupFacts {
            if (looseFingerprint == null || looseFingerprint.isBlank()) {
                throw new IllegalArgumentException("looseFingerprint is required");
            }
            normalizedFrames = normalizedFrames == null ? List.of() : List.copyOf(normalizedFrames);
        }
    }
    public record GroupLifecycle(long occurrenceCount, Instant firstSeen, Instant lastSeen,
                                 Instant createdAt, Instant updatedAt) {
        public GroupLifecycle {
            if (occurrenceCount < 0) throw new IllegalArgumentException("occurrenceCount must not be negative");
        }
    }
    /** Input value object keeps the public factory to one argument. */
    public record ErrorGroupSeed(GroupIdentity identity, GroupFacts facts,
                                 RootCauseAnalysis analysis, String clusterId) {
        public ErrorGroupSeed {
            if (identity == null || facts == null) {
                throw new IllegalArgumentException("identity and facts are required");
            }
        }
    }
}

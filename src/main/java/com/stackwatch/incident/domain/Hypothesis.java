package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Candidate root cause whose confidence is governed by independent evidence sources. */
public record Hypothesis(UUID id, UUID incidentId, String statement,
                         VerificationStatus verificationStatus, Double confidence, Instant createdAt) {

    public enum VerificationStatus {
        VERIFIED,
        PROVISIONAL,
        UNKNOWN
    }

    public Hypothesis {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        statement = requireText(statement, "statement");
        verificationStatus = Objects.requireNonNull(verificationStatus, "verificationStatus is required");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static Hypothesis assess(UUID id, UUID incidentId, String statement, Double confidence,
                                    List<Evidence> evidence, Instant createdAt) {
        long independentSources = (evidence == null ? List.<Evidence>of() : evidence).stream()
            .filter(item -> incidentId.equals(item.incidentId()))
            .map(Evidence::sourceType)
            .map(source -> source.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .count();
        VerificationStatus status = independentSources >= 2
            ? VerificationStatus.VERIFIED
            : independentSources == 1 ? VerificationStatus.PROVISIONAL : VerificationStatus.UNKNOWN;
        return new Hypothesis(id, incidentId, statement, status, confidence, createdAt);
    }

    public boolean needsHumanReview() {
        return verificationStatus != VerificationStatus.VERIFIED;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Candidate root cause whose verification status is derived from cited evidence. */
public record Hypothesis(UUID id, UUID incidentId, String statement, Double confidence,
                         List<Evidence> citedEvidence, Instant createdAt) {

    public enum VerificationStatus {
        VERIFIED,
        PROVISIONAL,
        UNKNOWN
    }

    public Hypothesis {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        statement = requireText(statement, "statement");
        citedEvidence = citedEvidence == null ? List.of() : List.copyOf(citedEvidence);
        UUID ownerId = incidentId;
        if (citedEvidence.stream().anyMatch(evidence -> !ownerId.equals(evidence.incidentId()))) {
            throw new IllegalArgumentException("cited evidence must belong to hypothesis incident");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public VerificationStatus verificationStatus() {
        long independentSources = citedEvidence.stream()
            .map(Evidence::sourceType)
            .map(source -> source.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .count();
        if (independentSources >= 2) {
            return VerificationStatus.VERIFIED;
        }
        return independentSources == 1 ? VerificationStatus.PROVISIONAL : VerificationStatus.UNKNOWN;
    }

    public boolean needsHumanReview() {
        return verificationStatus() != VerificationStatus.VERIFIED;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

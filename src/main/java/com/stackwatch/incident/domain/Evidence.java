package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted evidence retains only a redacted summary and source provenance. */
public record Evidence(UUID id, UUID incidentId, UUID observationId, String sourceType,
                       String redactedSummary, String provenance, Instant observedFrom,
                       Instant observedTo, String contentHash, Instant createdAt) {

    public Evidence {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        observationId = Objects.requireNonNull(observationId, "observationId is required");
        sourceType = requireText(sourceType, "sourceType");
        redactedSummary = requireText(redactedSummary, "redactedSummary");
        provenance = requireText(provenance, "provenance");
        contentHash = requireText(contentHash, "contentHash");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        if (observedFrom != null && observedTo != null && observedTo.isBefore(observedFrom)) {
            throw new IllegalArgumentException("observedTo must not precede observedFrom");
        }
    }

    /** Ensures evidence is derived only from an Observation representing usable source output. */
    public static void requireEligibleObservation(Observation observation) {
        Objects.requireNonNull(observation, "observation is required");
        if (observation.status().equalsIgnoreCase("FAILURE")
            || observation.status().equalsIgnoreCase("REJECTED")) {
            throw new IllegalArgumentException(
                "evidence cannot reference a failed or rejected observation");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Redacted, provenance-bearing result of a read-only tool call. */
public record Observation(UUID id, UUID incidentId, UUID stepId, String sourceType, String status,
                          String redactedSummary, String provenance, Instant observedFrom,
                          Instant observedTo, String contentHash, Instant createdAt) {

    public Observation {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        sourceType = requireText(sourceType, "sourceType");
        status = requireText(status, "status");
        redactedSummary = requireText(redactedSummary, "redactedSummary");
        provenance = requireText(provenance, "provenance");
        contentHash = requireText(contentHash, "contentHash");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        if (observedFrom != null && observedTo != null && observedTo.isBefore(observedFrom)) {
            throw new IllegalArgumentException("observedTo must not precede observedFrom");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

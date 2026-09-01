package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One decision/action outcome in an investigation audit trail. */
public record InvestigationStep(UUID id, UUID incidentId, int sequenceNumber, AgentDecision decision,
                                String outcome, Instant createdAt) {

    public InvestigationStep {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        decision = Objects.requireNonNull(decision, "decision is required");
        outcome = requireText(outcome, "outcome");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

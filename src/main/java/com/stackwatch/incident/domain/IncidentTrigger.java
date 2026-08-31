package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lightweight record of a signal that requested or reused an incident. */
public record IncidentTrigger(UUID id, String triggerType, String note, Instant createdAt) {

    public IncidentTrigger {
        id = Objects.requireNonNull(id, "id is required");
        triggerType = requireText(triggerType, "triggerType");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

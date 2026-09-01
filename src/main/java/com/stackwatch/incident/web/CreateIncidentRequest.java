package com.stackwatch.incident.web;

import java.util.Objects;

/** Request for a manual investigation of an existing Fast Path cluster. */
public record CreateIncidentRequest(String clusterId, String note) {

    public CreateIncidentRequest {
        clusterId = requireText(clusterId, "clusterId");
        note = note == null || note.isBlank() ? null : note.trim();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}

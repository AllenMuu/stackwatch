package com.stackwatch.incident.toolset;

import com.stackwatch.incident.domain.Incident;
import java.util.Objects;

/**
 * Server-owned incident identity scope passed to every read-only adapter.
 *
 * <p>It intentionally contains no query text, endpoint, credentials, or execution command.</p>
 */
public record ToolScope(String applicationName, String environment, String clusterId) {

    public ToolScope {
        applicationName = requireText(applicationName, "applicationName");
        environment = requireText(environment, "environment");
        clusterId = requireText(clusterId, "clusterId");
    }

    /** Derives the only permitted scope from the persisted Incident identity. */
    public static ToolScope fromIncident(Incident incident) {
        Incident requiredIncident = Objects.requireNonNull(incident, "incident is required");
        return new ToolScope(requiredIncident.applicationName(), requiredIncident.environment(),
            requiredIncident.clusterId());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

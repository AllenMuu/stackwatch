package com.stackwatch.incident.toolset;

/**
 * Fixed incident identity scope passed to every read-only adapter.
 *
 * <p>It intentionally contains no query text, endpoint, credentials, or execution command.</p>
 */
public record ToolScope(String applicationName, String environment, String clusterId) {

    public ToolScope {
        applicationName = requireText(applicationName, "applicationName");
        environment = requireText(environment, "environment");
        clusterId = requireText(clusterId, "clusterId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

package com.stackwatch.incident.toolset;

import java.util.Map;
import java.util.Objects;

/** Untrusted tool request that must be accepted by {@link ToolExecutor} before an adapter runs. */
public record ToolRequest(String toolName, ToolScope scope, Map<String, String> suppliedInputs) {

    public ToolRequest {
        toolName = requireText(toolName, "toolName");
        scope = Objects.requireNonNull(scope, "scope is required");
        suppliedInputs = suppliedInputs == null ? Map.of() : Map.copyOf(suppliedInputs);
    }

    /** Creates the only supported request shape: a configured tool over a fixed incident scope. */
    public static ToolRequest forIncident(Toolset toolset, ToolScope scope) {
        Objects.requireNonNull(toolset, "toolset is required");
        if (toolset == Toolset.UNKNOWN) {
            throw new IllegalArgumentException("unknown toolset cannot be requested");
        }
        return new ToolRequest(toolset.configuredName(), scope, Map.of());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

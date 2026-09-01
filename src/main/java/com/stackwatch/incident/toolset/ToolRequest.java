package com.stackwatch.incident.toolset;

import java.util.Objects;

/** Untrusted tool name request; the executor owns all adapter scope construction. */
public record ToolRequest(String toolName) {

    public ToolRequest {
        toolName = requireText(toolName, "toolName");
    }

    /** Creates a request from an application-configured Toolset. */
    public static ToolRequest forToolset(Toolset toolset) {
        Objects.requireNonNull(toolset, "toolset is required");
        if (toolset == Toolset.UNKNOWN) {
            throw new IllegalArgumentException("unknown toolset cannot be requested");
        }
        return new ToolRequest(toolset.configuredName());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

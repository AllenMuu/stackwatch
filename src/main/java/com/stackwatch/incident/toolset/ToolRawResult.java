package com.stackwatch.incident.toolset;

import java.time.Instant;

/** Adapter-local output before ToolExecutor redacts it and assigns a content hash. */
public record ToolRawResult(String summary, String provenance, Instant observedFrom,
                            Instant observedTo) {

    public ToolRawResult {
        summary = requireText(summary, "summary");
        provenance = requireText(provenance, "provenance");
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

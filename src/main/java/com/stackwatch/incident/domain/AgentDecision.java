package com.stackwatch.incident.domain;

import java.util.Map;

/** Structured, auditable decision summary. Deliberative chain-of-thought is not retained. */
public record AgentDecision(String decisionType, String summary, String toolset,
                            Map<String, String> inputs) {

    public AgentDecision {
        decisionType = requireText(decisionType, "decisionType");
        summary = requireText(summary, "summary");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public AgentDecision(String decisionType, String summary, String toolset) {
        this(decisionType, summary, toolset, Map.of());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

package com.stackwatch.incident.skills;

import com.stackwatch.incident.toolset.Toolset;
import java.util.List;

/** Versioned procedural guidance selected deterministically for a Deep Path incident. */
public record IncidentSkill(String id, String version, List<String> requiredSignals,
                            List<Toolset> toolsets, String instructions) {

    public IncidentSkill {
        id = requireText(id, "id");
        version = requireText(version, "version");
        requiredSignals = requiredSignals == null ? List.of() : List.copyOf(requiredSignals);
        toolsets = toolsets == null ? List.of() : List.copyOf(toolsets);
        instructions = requireText(instructions, "instructions");
        if (requiredSignals.isEmpty()) {
            throw new IllegalArgumentException("requiredSignals must not be empty");
        }
        if (toolsets.isEmpty()) {
            throw new IllegalArgumentException("toolsets must not be empty");
        }
        if (requiredSignals.stream().anyMatch(signal -> signal == null || signal.isBlank())) {
            throw new IllegalArgumentException("requiredSignals must contain text");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

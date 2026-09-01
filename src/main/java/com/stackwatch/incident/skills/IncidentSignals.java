package com.stackwatch.incident.skills;

import java.util.List;

/** Exception-derived signals used by the deterministic Skill matcher. */
public record IncidentSignals(String exceptionType, String exceptionMessage,
                             List<String> stackTrace) {

    public IncidentSignals {
        exceptionType = exceptionType == null ? "" : exceptionType;
        exceptionMessage = exceptionMessage == null ? "" : exceptionMessage;
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }

    String searchableText() {
        return exceptionType + "\n" + exceptionMessage + "\n" + String.join("\n", stackTrace);
    }
}

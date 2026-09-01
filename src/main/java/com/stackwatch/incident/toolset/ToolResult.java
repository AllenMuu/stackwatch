package com.stackwatch.incident.toolset;

import com.stackwatch.incident.domain.Observation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Normalized, redacted Toolset output suitable for persistence only as an Observation. */
public record ToolResult(Toolset toolset, ToolResultStatus status, String redactedSummary,
                         String provenance, Instant observedFrom, Instant observedTo,
                         String contentHash, Optional<String> missingEvidence) {

    public ToolResult {
        toolset = Objects.requireNonNull(toolset, "toolset is required");
        status = Objects.requireNonNull(status, "status is required");
        redactedSummary = requireText(redactedSummary, "redactedSummary");
        provenance = requireText(provenance, "provenance");
        contentHash = requireText(contentHash, "contentHash");
        missingEvidence = missingEvidence == null ? Optional.empty() : missingEvidence.map(
            value -> requireText(value, "missingEvidence"));
        if (observedFrom != null && observedTo != null && observedTo.isBefore(observedFrom)) {
            throw new IllegalArgumentException("observedTo must not precede observedFrom");
        }
    }

    /** Converts every outcome, including failures and policy rejections, into an Observation. */
    public Observation toObservation(UUID observationId, UUID incidentId, UUID stepId,
                                     Instant createdAt) {
        return new Observation(observationId, incidentId, stepId,
            toolset.configuredName().toUpperCase(), status.name(), redactedSummary, provenance,
            observedFrom, observedTo, contentHash, createdAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

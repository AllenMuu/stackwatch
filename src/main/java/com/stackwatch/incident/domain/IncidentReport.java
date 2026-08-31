package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Separate, structured Deep Path conclusion; it never extends Fast Path root-cause output. */
public record IncidentReport(UUID id, UUID incidentId, List<Hypothesis> hypotheses,
                             List<Evidence> evidence, List<String> missingEvidence,
                             String recommendation, IncidentStatus reviewOutcome, Instant createdAt) {

    public IncidentReport {
        id = Objects.requireNonNull(id, "id is required");
        incidentId = Objects.requireNonNull(incidentId, "incidentId is required");
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        reviewOutcome = Objects.requireNonNull(reviewOutcome, "reviewOutcome is required");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public static IncidentReport forInvestigation(UUID id, UUID incidentId, List<Hypothesis> hypotheses,
                                                   List<Evidence> evidence, List<String> missingEvidence,
                                                   String recommendation, Instant createdAt) {
        List<Hypothesis> safeHypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        IncidentStatus reviewOutcome = safeHypotheses.stream().anyMatch(Hypothesis::needsHumanReview)
            ? IncidentStatus.NEEDS_HUMAN_REVIEW
            : IncidentStatus.COMPLETED;
        return new IncidentReport(id, incidentId, safeHypotheses, evidence, missingEvidence,
            recommendation, reviewOutcome, createdAt);
    }

    public boolean requiresHumanReview() {
        return reviewOutcome == IncidentStatus.NEEDS_HUMAN_REVIEW
            || hypotheses.stream().anyMatch(Hypothesis::needsHumanReview);
    }
}

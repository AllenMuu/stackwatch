package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        UUID ownerId = incidentId;
        Map<UUID, Evidence> evidenceById = evidence.stream().collect(Collectors.toUnmodifiableMap(
            Evidence::id, Function.identity()));
        if (evidenceById.size() != evidence.size()) {
            throw new IllegalArgumentException("report evidence IDs must be unique");
        }
        if (evidence.stream().anyMatch(item -> !ownerId.equals(item.incidentId()))) {
            throw new IllegalArgumentException("report evidence must belong to report incident");
        }
        for (Hypothesis hypothesis : hypotheses) {
            if (!incidentId.equals(hypothesis.incidentId())) {
                throw new IllegalArgumentException("hypothesis must belong to report incident");
            }
            for (Evidence citation : hypothesis.citedEvidence()) {
                if (!citation.equals(evidenceById.get(citation.id()))) {
                    throw new IllegalArgumentException("hypothesis citations must be present in report evidence");
                }
            }
        }
        reviewOutcome = requiresReviewFromEvidence(hypotheses, evidence)
            ? IncidentStatus.NEEDS_HUMAN_REVIEW
            : reviewOutcome == IncidentStatus.NEEDS_HUMAN_REVIEW
                ? IncidentStatus.NEEDS_HUMAN_REVIEW
                : IncidentStatus.COMPLETED;
    }

    public static IncidentReport forInvestigation(UUID id, UUID incidentId, List<Hypothesis> hypotheses,
                                                   List<Evidence> evidence, List<String> missingEvidence,
                                                   String recommendation, Instant createdAt) {
        return new IncidentReport(id, incidentId, hypotheses, evidence, missingEvidence,
            recommendation, IncidentStatus.COMPLETED, createdAt);
    }

    public boolean requiresHumanReview() {
        return reviewOutcome == IncidentStatus.NEEDS_HUMAN_REVIEW
            || requiresReviewFromEvidence(hypotheses, evidence);
    }

    private static boolean requiresReviewFromEvidence(List<Hypothesis> hypotheses, List<Evidence> evidence) {
        return evidence.isEmpty()
            || hypotheses.isEmpty()
            || hypotheses.stream().anyMatch(Hypothesis::needsHumanReview);
    }
}

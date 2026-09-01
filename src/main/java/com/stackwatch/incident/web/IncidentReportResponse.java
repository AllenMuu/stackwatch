package com.stackwatch.incident.web;

import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Hypothesis;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only structured Deep Path report. */
public record IncidentReportResponse(UUID id, UUID incidentId, List<Hypothesis> hypotheses,
                                     List<Evidence> evidence, List<String> missingEvidence,
                                     String recommendation, IncidentStatus reviewOutcome,
                                     Instant createdAt) {

    public static IncidentReportResponse from(IncidentReport report) {
        return new IncidentReportResponse(report.id(), report.incidentId(), report.hypotheses(),
            report.evidence(), report.missingEvidence(), report.recommendation(),
            report.reviewOutcome(), report.createdAt());
    }
}

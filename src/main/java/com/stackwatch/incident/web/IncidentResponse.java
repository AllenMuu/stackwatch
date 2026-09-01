package com.stackwatch.incident.web;

import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.InvestigationStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only representation of incident state and its decision/trigger audit trail. */
public record IncidentResponse(UUID id, String applicationName, String environment, String clusterId,
                               IncidentStatus status, List<IncidentTrigger> triggers,
                               List<InvestigationStep> steps, Instant createdAt, Instant updatedAt,
                               Instant startedAt, Instant completedAt, String failureReason) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(incident.id(), incident.applicationName(), incident.environment(),
            incident.clusterId(), incident.status(), incident.triggers(), incident.steps(),
            incident.createdAt(), incident.updatedAt(), incident.startedAt(), incident.completedAt(),
            incident.failureReason());
    }
}

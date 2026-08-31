package com.stackwatch.incident.repository;

import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.InvestigationStep;
import com.stackwatch.incident.domain.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable storage boundary for incident lifecycle state and its append-only audit records. */
public interface IncidentRepository {

    Incident createOrReuse(String applicationName, String environment, String clusterId,
                           IncidentTrigger trigger);

    Optional<Incident> findById(UUID incidentId);

    void update(Incident incident);

    void appendStep(InvestigationStep step);

    void appendObservation(Observation observation);

    void appendEvidence(Evidence evidence);

    List<Observation> findObservations(UUID incidentId);

    List<Evidence> findEvidence(UUID incidentId);

    void saveReport(IncidentReport report);

    Optional<IncidentReport> findReport(UUID incidentId);

    int markStaleRunningFailed(Instant staleBefore, Instant now);
}

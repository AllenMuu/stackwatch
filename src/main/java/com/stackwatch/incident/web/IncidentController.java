package com.stackwatch.incident.web;

import com.stackwatch.analyzer.ClusterRepository;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.runtime.InvestigationScheduler;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP boundary for manually requesting and inspecting a Deep Path investigation. */
@RestController
@RequestMapping("/incidents")
@ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
public class IncidentController {

    private final IncidentRepository incidentRepository;
    private final ClusterRepository clusterRepository;
    private final InvestigationScheduler investigationScheduler;

    public IncidentController(IncidentRepository incidentRepository,
                              ClusterRepository clusterRepository,
                              InvestigationScheduler investigationScheduler) {
        this.incidentRepository = incidentRepository;
        this.clusterRepository = clusterRepository;
        this.investigationScheduler = investigationScheduler;
    }

    @PostMapping
    public IncidentResponse create(@RequestBody CreateIncidentRequest request) {
        ErrorCluster cluster = clusterRepository.findById(request.clusterId())
            .orElseThrow(() -> notFound("cluster", request.clusterId()));
        IncidentTrigger trigger = new IncidentTrigger(UUID.randomUUID(), "MANUAL", request.note(),
            Instant.now());
        // ErrorCluster has no environment dimension. Reuse any active incident for this
        // application/cluster pair and preserve its server-owned environment.
        String environment = incidentRepository
            .findActiveByApplicationAndCluster(cluster.appName(), cluster.clusterId())
            .map(Incident::environment)
            .orElse("unknown");
        Incident incident = incidentRepository.createOrReuse(cluster.appName(), environment,
            cluster.clusterId(), trigger);
        investigationScheduler.schedule(incident.id());
        return IncidentResponse.from(incident);
    }

    @GetMapping("/{incidentId}")
    public IncidentResponse status(@PathVariable UUID incidentId) {
        return IncidentResponse.from(findIncident(incidentId));
    }

    @GetMapping("/{incidentId}/report")
    public IncidentReportResponse report(@PathVariable UUID incidentId) {
        // Check the incident first so a missing report cannot reveal whether an arbitrary ID was
        // ever present in the report table.
        findIncident(incidentId);
        IncidentReport report = incidentRepository.findReport(incidentId)
            .orElseThrow(() -> notFound("report", incidentId.toString()));
        return IncidentReportResponse.from(report);
    }

    private Incident findIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId)
            .orElseThrow(() -> notFound("incident", incidentId.toString()));
    }

    private static ResponseStatusException notFound(String resource, String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found: " + id);
    }
}

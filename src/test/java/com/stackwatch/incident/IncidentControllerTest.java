package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stackwatch.analyzer.ClusterRepository;
import com.stackwatch.domain.ClusterStatus;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.RootCauseAnalysis;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.runtime.InvestigationScheduler;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.web.CreateIncidentRequest;
import com.stackwatch.incident.web.IncidentController;
import com.stackwatch.incident.web.IncidentReportResponse;
import com.stackwatch.incident.web.IncidentResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class IncidentControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T01:00:00Z");

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private ClusterRepository clusterRepository;

    @Mock
    private InvestigationScheduler investigationScheduler;

    @Test
    void createsManualIncidentFromExistingClusterAndSchedulesIt() {
        ErrorCluster cluster = cluster("cluster-42");
        Incident incident = incident(cluster, IncidentStatus.PENDING);
        when(clusterRepository.findById(cluster.clusterId())).thenReturn(Optional.of(cluster));
        when(incidentRepository.createOrReuse(
            eq(cluster.appName()), eq("unknown"), eq(cluster.clusterId()),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(incident);

        IncidentResponse response = controller().create(new CreateIncidentRequest("cluster-42", "check timeout"));

        assertThat(response.id()).isEqualTo(incident.id());
        assertThat(response.status()).isEqualTo(IncidentStatus.PENDING);
        verify(investigationScheduler).schedule(incident.id());
    }

    @Test
    void manualRequestReusesActiveIncidentEnvironmentForSameApplicationAndCluster() {
        ErrorCluster cluster = cluster("cluster-42");
        Incident active = Incident.pending(UUID.randomUUID(), cluster.appName(), "prod",
            cluster.clusterId(), List.of(new IncidentTrigger(UUID.randomUUID(), "FAST_PATH", null, NOW)), NOW)
            .start(NOW.plusSeconds(1));
        when(clusterRepository.findById(cluster.clusterId())).thenReturn(Optional.of(cluster));
        when(incidentRepository.findActiveByApplicationAndCluster(cluster.appName(), cluster.clusterId()))
            .thenReturn(Optional.of(active));
        when(incidentRepository.createOrReuse(eq(cluster.appName()), eq(active.environment()),
            eq(cluster.clusterId()), org.mockito.ArgumentMatchers.any())).thenReturn(active);

        IncidentResponse response = controller().create(new CreateIncidentRequest("cluster-42", null));

        assertThat(response.id()).isEqualTo(active.id());
        verify(incidentRepository).createOrReuse(eq(cluster.appName()), eq("prod"),
            eq(cluster.clusterId()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsManualRequestForUnknownClusterWithoutCreatingIncident() {
        when(clusterRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().create(new CreateIncidentRequest("missing", null)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404));
    }

    @Test
    void exposesStatusAndReportAsReadOnlyResponses() {
        UUID incidentId = UUID.randomUUID();
        Incident incident = incident("cluster-42", incidentId, IncidentStatus.COMPLETED);
        IncidentReport report = new IncidentReport(UUID.randomUUID(), incidentId, List.of(), List.of(),
            List.of("Trace"), "Collect more evidence", IncidentStatus.NEEDS_HUMAN_REVIEW, NOW);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.findReport(incidentId)).thenReturn(Optional.of(report));

        assertThat(controller().status(incidentId).status()).isEqualTo(IncidentStatus.COMPLETED);
        IncidentReportResponse reportResponse = controller().report(incidentId);
        assertThat(reportResponse.reviewOutcome()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(reportResponse.missingEvidence()).containsExactly("Trace");
    }

    @Test
    void missingIncidentAndMissingReportReturnNotFound() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller().status(incidentId))
            .isInstanceOf(ResponseStatusException.class);

        Incident incident = incident("cluster-42", incidentId, IncidentStatus.RUNNING);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.findReport(incidentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller().report(incidentId))
            .isInstanceOf(ResponseStatusException.class);
    }

    private IncidentController controller() {
        return new IncidentController(incidentRepository, clusterRepository, investigationScheduler);
    }

    private static ErrorCluster cluster(String clusterId) {
        RootCauseAnalysis analysis = new RootCauseAnalysis("downstream timeout", "dependency",
            "high", 0.8, "inspect downstream", List.of("logs"), false);
        return new ErrorCluster(clusterId, "fingerprint", "orders", "FeignException", 1,
            NOW, NOW, ClusterStatus.ANALYZED, analysis, new float[0]);
    }

    private static Incident incident(ErrorCluster cluster, IncidentStatus status) {
        return incident(cluster.clusterId(), UUID.randomUUID(), status);
    }

    private static Incident incident(String clusterId, UUID id, IncidentStatus status) {
        IncidentTrigger trigger = new IncidentTrigger(UUID.randomUUID(), "MANUAL", "check", NOW);
        Incident incident = Incident.pending(id, "orders", "unknown", clusterId, List.of(trigger), NOW);
        if (status == IncidentStatus.PENDING) {
            return incident;
        }
        Incident running = incident.start(NOW.plusSeconds(1));
        return status == IncidentStatus.RUNNING
            ? running
            : running.transitionTo(status, NOW.plusSeconds(2), null);
    }
}

package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.toolset.ToolResultStatus;
import com.stackwatch.incident.toolset.Toolset;
import com.stackwatch.metrics.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class IncidentMetricsTest {

    @Test
    void deepPathMetricsUseOnlyBoundedLabelsAndNeverExposeIdentifiersOrMessages() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry);

        metrics.recordIncidentTrigger("manual note incident-123");
        metrics.recordIncidentLifecycle(IncidentStatus.RUNNING);
        metrics.recordIncidentToolResult(Toolset.LOGS, ToolResultStatus.FAILURE);
        metrics.recordIncidentEvidenceCount(2);
        metrics.recordIncidentReviewOutcome(IncidentStatus.NEEDS_HUMAN_REVIEW);
        metrics.recordIncidentEvaluation("passed with incident-123");
        metrics.recordIncidentDuration(1_000_000);

        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getName()).startsWith("stackwatch.");
            assertThat(meter.getId().getTags()).noneMatch(tag ->
                tag.getValue().contains("incident-123") || tag.getValue().contains("manual note"));
        });
        assertThat(registry.get("stackwatch.incident_trigger_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("stackwatch.incident_lifecycle_total")
            .tag("state", "running").counter().count()).isEqualTo(1);
        assertThat(registry.get("stackwatch.incident_tool_result_total")
            .tag("tool", "logs").tag("status", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("stackwatch.incident_evidence_count").summary().count()).isEqualTo(1);
        assertThat(registry.get("stackwatch.incident_review_outcome_total")
            .tag("outcome", "needs_human_review").counter().count()).isEqualTo(1);
        assertThat(registry.get("stackwatch.incident_evaluation_total")
            .tag("result", "other").counter().count()).isEqualTo(1);
    }
}

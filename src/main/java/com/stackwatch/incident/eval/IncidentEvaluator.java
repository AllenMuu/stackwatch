package com.stackwatch.incident.eval;

import com.stackwatch.incident.domain.AgentDecision;
import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Hypothesis;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.InvestigationStep;
import com.stackwatch.incident.domain.Observation;
import com.stackwatch.incident.runtime.InvestigationContext;
import com.stackwatch.incident.runtime.ScriptedAgentDecisionProvider;
import com.stackwatch.incident.toolset.ToolExecutor;
import com.stackwatch.incident.toolset.ToolRequest;
import com.stackwatch.incident.toolset.ToolResult;
import com.stackwatch.incident.toolset.ToolResultStatus;
import com.stackwatch.incident.toolset.ToolRegistry;
import com.stackwatch.incident.toolset.Toolset;
import com.stackwatch.incident.toolset.GitDeploymentStubAdapter;
import com.stackwatch.incident.toolset.LogsStubAdapter;
import com.stackwatch.incident.toolset.TraceStubAdapter;
import com.stackwatch.metrics.AnalysisMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Runs the versioned fixture and reports contract violations instead of depending on a live provider. */
public final class IncidentEvaluator {

    private final ToolExecutor toolExecutor;
    private final AnalysisMetrics metrics;

    public IncidentEvaluator() {
        this(new ToolExecutor(new ToolRegistry(List.of(
            new LogsStubAdapter(), new TraceStubAdapter(), new GitDeploymentStubAdapter()))), null);
    }

    public IncidentEvaluator(ToolExecutor toolExecutor) {
        this(toolExecutor, null);
    }

    public IncidentEvaluator(ToolExecutor toolExecutor, AnalysisMetrics metrics) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor is required");
        this.metrics = metrics;
    }

    /** Evaluates the bundled fixture using its scripted decisions and fixed stub adapters. */
    public EvaluationResult evaluate() {
        return evaluate(FeignTimeoutFixture.incident(),
            new ScriptedAgentDecisionProvider(FeignTimeoutFixture.decisions()));
    }

    /** Evaluates a supplied incident/provider pair, useful for policy regression tests. */
    public EvaluationResult evaluate(Incident incident, ScriptedAgentDecisionProvider provider) {
        Objects.requireNonNull(incident, "incident is required");
        Objects.requireNonNull(provider, "provider is required");
        Incident running = incident.start(Instant.now());
        List<InvestigationStep> steps = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        List<Toolset> invoked = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        int sequence = 0;
        String recommendation = "A downstream Feign read timeout followed a deployment change";
        while (sequence < FeignTimeoutFixture.decisions().size()) {
            AgentDecision decision = provider.nextDecision(new InvestigationContext(running, observations,
                List.of(), sequence, invoked.size(), Duration.ZERO)).orElse(null);
            if (decision == null) {
                break;
            }
            sequence++;
            if ("COMPLETE".equalsIgnoreCase(decision.decisionType())) {
                recommendation = decision.summary();
                break;
            }
            Toolset selected = Toolset.fromConfiguredName(decision.toolset()).orElse(null);
            if (selected == null || FeignTimeoutFixture.forbiddenToolNames().contains(decision.toolset())) {
                violations.add("forbidden or unknown Toolset requested: " + decision.toolset());
                continue;
            }
            invoked.add(selected);
            ToolResult result = toolExecutor.execute(running, new ToolRequest(decision.toolset()));
            Instant createdAt = Instant.now();
            InvestigationStep step = new InvestigationStep(UUID.randomUUID(), running.id(), sequence,
                decision, result.status().name(), createdAt);
            steps.add(step);
            Observation observation = result.toObservation(UUID.randomUUID(), running.id(), step.id(), createdAt);
            observations.add(observation);
            if (result.status() == ToolResultStatus.SUCCESS) {
                evidence.add(new Evidence(UUID.randomUUID(), running.id(), observation.id(),
                    result.toolset().configuredName().toUpperCase(), result.redactedSummary(),
                    result.provenance(), result.observedFrom(), result.observedTo(), result.contentHash(), createdAt));
            }
            if (metrics != null) {
                metrics.recordIncidentToolResult(result.toolset(), result.status());
            }
        }
        Set<Toolset> invokedSet = new HashSet<>(invoked);
        for (Toolset required : FeignTimeoutFixture.requiredToolsets()) {
            if (!invokedSet.contains(required)) {
                violations.add("required Toolset was not invoked: " + required.configuredName());
            }
        }
        Hypothesis hypothesis = new Hypothesis(UUID.randomUUID(), running.id(),
            "A downstream Feign read timeout followed a deployment change", 0.9, evidence,
            Instant.now());
        IncidentReport report = IncidentReport.forInvestigation(UUID.randomUUID(), running.id(),
            List.of(hypothesis), evidence, violations.isEmpty() ? List.of() : List.copyOf(violations),
            recommendation + " (fixture " + FeignTimeoutFixture.VERSION + ")",
            Instant.now());
        boolean passed = violations.isEmpty()
            && invokedSet.equals(FeignTimeoutFixture.requiredToolsets())
            && evidence.size() == FeignTimeoutFixture.requiredToolsets().size()
            && report.reviewOutcome() == IncidentStatus.COMPLETED;
        if (metrics != null) {
            metrics.recordIncidentEvidenceCount(evidence.size());
            metrics.recordIncidentReviewOutcome(report.reviewOutcome());
            metrics.recordIncidentEvaluation(passed ? "passed" : "failed");
        }
        return new EvaluationResult(FeignTimeoutFixture.VERSION, passed, List.copyOf(invoked),
            List.copyOf(observations), List.copyOf(evidence), report, List.copyOf(violations));
    }

    /** Stable evaluation output suitable for CI assertions and human inspection. */
    public record EvaluationResult(String fixtureVersion, boolean passed, List<Toolset> invokedToolsets,
                                   List<Observation> observations, List<Evidence> evidence,
                                   IncidentReport report, List<String> violations) {

        public EvaluationResult {
            fixtureVersion = Objects.requireNonNull(fixtureVersion, "fixtureVersion is required");
            invokedToolsets = invokedToolsets == null ? List.of() : List.copyOf(invokedToolsets);
            observations = observations == null ? List.of() : List.copyOf(observations);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            report = Objects.requireNonNull(report, "report is required");
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}

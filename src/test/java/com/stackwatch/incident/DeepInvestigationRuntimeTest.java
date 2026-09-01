package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.config.IncidentProperties;
import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.InvestigationStep;
import com.stackwatch.incident.domain.Observation;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.runtime.AgentDecisionProvider;
import com.stackwatch.incident.runtime.DeepInvestigationRuntime;
import com.stackwatch.incident.runtime.InvestigationContext;
import com.stackwatch.incident.runtime.ScriptedAgentDecisionProvider;
import com.stackwatch.incident.skills.IncidentSkillLoader;
import com.stackwatch.incident.skills.SkillMatcher;
import com.stackwatch.incident.toolset.GitDeploymentStubAdapter;
import com.stackwatch.incident.toolset.LogsStubAdapter;
import com.stackwatch.incident.toolset.ToolAdapter;
import com.stackwatch.incident.toolset.ToolExecutor;
import com.stackwatch.incident.toolset.ToolRegistry;
import com.stackwatch.incident.toolset.TraceStubAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DeepInvestigationRuntimeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Test
    void executesSuccessfulToolsPersistsEvidenceAndCompletesWithTwoSources() {
        InMemoryIncidents repository = new InMemoryIncidents();
        DeepInvestigationRuntime runtime = runtime(repository, new ScriptedAgentDecisionProvider(List.of(
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "inspect logs", "logs"),
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "inspect trace", "trace"),
            new com.stackwatch.incident.domain.AgentDecision("COMPLETE", "Feign timeout downstream", null))));

        Incident finished = runtime.run(repository.incident.id());

        assertThat(finished.status()).isEqualTo(IncidentStatus.COMPLETED);
        assertThat(repository.steps).hasSize(3);
        assertThat(repository.observations).hasSize(2);
        assertThat(repository.evidence).hasSize(2);
        assertThat(repository.report).isPresent().get().extracting(IncidentReport::reviewOutcome)
            .isEqualTo(IncidentStatus.COMPLETED);
    }

    @Test
    void stopsWhenMaximumToolCallsIsReachedAndRequiresReview() {
        InMemoryIncidents repository = new InMemoryIncidents();
        DeepInvestigationRuntime runtime = runtime(repository, new ScriptedAgentDecisionProvider(List.of(
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "one", "logs"),
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "two", "trace"),
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "three", "logs"))),
            new IncidentProperties(false, 6, 2, Duration.ofSeconds(5), Duration.ofSeconds(30)));

        Incident finished = runtime.run(repository.incident.id());

        assertThat(finished.status()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(finished.failureReason()).isEqualTo("maximum tool-call count exceeded");
        assertThat(repository.observations).hasSize(2);
        assertThat(repository.steps).extracting(InvestigationStep::outcome)
            .last().isEqualTo("maximum tool-call count exceeded");
    }

    @Test
    void providerFailureIsTerminalFailureAndIsAudited() {
        InMemoryIncidents repository = new InMemoryIncidents();
        AgentDecisionProvider failing = context -> { throw new IllegalStateException("provider offline"); };
        DeepInvestigationRuntime runtime = runtime(repository, failing);

        Incident finished = runtime.run(repository.incident.id());

        assertThat(finished.status()).isEqualTo(IncidentStatus.FAILED);
        assertThat(repository.steps).extracting(InvestigationStep::outcome)
            .containsExactly("decision provider failure");
    }

    @Test
    void toolTimeoutBecomesObservationAndReportRequiresReview() {
        InMemoryIncidents repository = new InMemoryIncidents();
        ToolAdapter slow = new ToolAdapter() {
            @Override public com.stackwatch.incident.toolset.Toolset toolset() {
                return com.stackwatch.incident.toolset.Toolset.LOGS;
            }
            @Override public com.stackwatch.incident.toolset.ToolRawResult execute(
                com.stackwatch.incident.toolset.ToolScope scope) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return new com.stackwatch.incident.toolset.ToolRawResult("late", "slow", CREATED_AT, CREATED_AT);
            }
        };
        DeepInvestigationRuntime runtime = runtime(repository, new ScriptedAgentDecisionProvider(List.of(
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "inspect", "logs"),
            new com.stackwatch.incident.domain.AgentDecision("COMPLETE", "timeout", null))),
            new IncidentProperties(false, 6, 3, Duration.ofMillis(20), Duration.ofSeconds(2)),
            new ToolRegistry(List.of(slow)));

        Incident finished = runtime.run(repository.incident.id());

        assertThat(repository.observations).singleElement().extracting(Observation::status)
            .isEqualTo("FAILURE");
        assertThat(finished.status()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(repository.report.orElseThrow().missingEvidence())
            .anyMatch(value -> value.contains("Missing evidence"));
    }

    @Test
    void ungroundedCompletionCannotBeVerifiedByUnrelatedSuccessfulSources() {
        InMemoryIncidents repository = new InMemoryIncidents();
        DeepInvestigationRuntime runtime = runtime(repository, new ScriptedAgentDecisionProvider(List.of(
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "inspect logs", "logs"),
            new com.stackwatch.incident.domain.AgentDecision("TOOL_CALL", "inspect trace", "trace"),
            new com.stackwatch.incident.domain.AgentDecision("COMPLETE", "database corruption", null))));

        Incident finished = runtime.run(repository.incident.id());

        assertThat(finished.status()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(repository.report.orElseThrow().missingEvidence())
            .anyMatch(value -> value.contains("not grounded"));
    }

    private static DeepInvestigationRuntime runtime(InMemoryIncidents repository,
                                                     AgentDecisionProvider provider) {
        return runtime(repository, provider,
            new IncidentProperties(false, 6, 3, Duration.ofSeconds(5), Duration.ofSeconds(30)),
            new ToolRegistry(List.of(new LogsStubAdapter(), new TraceStubAdapter(),
                new GitDeploymentStubAdapter())));
    }

    private static DeepInvestigationRuntime runtime(InMemoryIncidents repository,
                                                     AgentDecisionProvider provider,
                                                     IncidentProperties properties) {
        return runtime(repository, provider, properties,
            new ToolRegistry(List.of(new LogsStubAdapter(), new TraceStubAdapter(),
                new GitDeploymentStubAdapter())));
    }

    private static DeepInvestigationRuntime runtime(InMemoryIncidents repository,
                                                     AgentDecisionProvider provider,
                                                     IncidentProperties properties,
                                                     ToolRegistry registry) {
        IncidentSkillLoader loader = new IncidentSkillLoader();
        return new DeepInvestigationRuntime(repository, new SkillMatcher(loader.loadDefaults()),
            loader.loadDefaults(), new ToolExecutor(registry), provider, properties,
            Executors.newCachedThreadPool(), Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));
    }

    private static final class InMemoryIncidents implements IncidentRepository {
        private final Incident incident = Incident.pending(UUID.randomUUID(), "orders", "prod", "cluster-42",
            List.of(new IncidentTrigger(UUID.randomUUID(), "FAST_PATH", "timeout", CREATED_AT)), CREATED_AT);
        private Incident current = incident;
        private final List<InvestigationStep> steps = new ArrayList<>();
        private final List<Observation> observations = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();
        private Optional<IncidentReport> report = Optional.empty();

        @Override public Incident createOrReuse(String a, String e, String c, IncidentTrigger t) { return current; }
        @Override public Optional<Incident> findById(UUID id) { return Optional.of(current); }
        @Override public Optional<Incident> startIfPending(UUID id, Instant at) {
            if (current.status() != IncidentStatus.PENDING) return Optional.empty();
            current = current.start(at); return Optional.of(current);
        }
        @Override public boolean updateIfCurrentStatus(Incident next, IncidentStatus expected) {
            if (current.status() != expected) return false;
            current = next; return true;
        }
        @Override public void appendStep(InvestigationStep step) { steps.add(step); }
        @Override public void appendObservation(Observation observation) { observations.add(observation); }
        @Override public void appendEvidence(Evidence item) { evidence.add(item); }
        @Override public List<Observation> findObservations(UUID id) { return List.copyOf(observations); }
        @Override public List<Evidence> findEvidence(UUID id) { return List.copyOf(evidence); }
        @Override public void saveReport(IncidentReport saved) { report = Optional.of(saved); }
        @Override public Optional<IncidentReport> findReport(UUID id) { return report; }
        @Override public int markStaleRunningFailed(Instant before, Instant now) { return 0; }
    }
}

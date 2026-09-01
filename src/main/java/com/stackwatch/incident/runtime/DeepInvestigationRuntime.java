package com.stackwatch.incident.runtime;

import com.stackwatch.config.IncidentProperties;
import com.stackwatch.incident.domain.AgentDecision;
import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Hypothesis;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.InvestigationStep;
import com.stackwatch.incident.domain.Observation;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.skills.IncidentSignals;
import com.stackwatch.incident.skills.IncidentSkill;
import com.stackwatch.incident.skills.SkillMatcher;
import com.stackwatch.incident.toolset.ToolExecutor;
import com.stackwatch.incident.toolset.ToolRequest;
import com.stackwatch.incident.toolset.ToolResult;
import com.stackwatch.incident.toolset.ToolResultStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * StackWatch-owned bounded investigation loop. The provider supplies decisions, while this class
 * owns state transitions, limits, persistence, and tool execution.
 */
public final class DeepInvestigationRuntime {

    private static final String COMPLETE = "COMPLETE";
    private static final String REVIEW = "REVIEW";
    private static final String TOOL_CALL = "TOOL_CALL";

    private final IncidentRepository repository;
    private final SkillMatcher skillMatcher;
    private final List<IncidentSkill> skills;
    private final ToolExecutor toolExecutor;
    private final AgentDecisionProvider decisionProvider;
    private final IncidentProperties properties;
    private final ExecutorService toolCallExecutor;
    private final Clock clock;

    public DeepInvestigationRuntime(IncidentRepository repository, SkillMatcher skillMatcher,
                                    List<IncidentSkill> skills, ToolExecutor toolExecutor,
                                    AgentDecisionProvider decisionProvider,
                                    IncidentProperties properties) {
        this(repository, skillMatcher, skills, toolExecutor, decisionProvider, properties,
            java.util.concurrent.Executors.newCachedThreadPool(), Clock.systemUTC());
    }

    public DeepInvestigationRuntime(IncidentRepository repository, SkillMatcher skillMatcher,
                                    List<IncidentSkill> skills, ToolExecutor toolExecutor,
                                    AgentDecisionProvider decisionProvider,
                                    IncidentProperties properties, ExecutorService toolCallExecutor,
                                    Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.skillMatcher = Objects.requireNonNull(skillMatcher, "skillMatcher is required");
        this.skills = skills == null ? List.of() : List.copyOf(skills);
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor is required");
        this.decisionProvider = Objects.requireNonNull(decisionProvider, "decisionProvider is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.toolCallExecutor = Objects.requireNonNull(toolCallExecutor, "toolCallExecutor is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Claims and executes a pending incident. A running or terminal incident is never restarted. */
    public Incident run(UUID incidentId) {
        return run(incidentId, new IncidentSignals("", "", List.of()));
    }

    /** Alias for callers that describe a worker invocation as an investigation. */
    public Incident investigate(UUID incidentId) {
        return run(incidentId);
    }

    /** Claims and executes a pending incident with the original Fast Path signals for Skill matching. */
    public Incident run(UUID incidentId, IncidentSignals signals) {
        Objects.requireNonNull(incidentId, "incidentId is required");
        Objects.requireNonNull(signals, "signals are required");
        Incident existing = repository.findById(incidentId)
            .orElseThrow(() -> new IllegalArgumentException("incident does not exist: " + incidentId));
        Optional<Incident> claimed = repository.startIfPending(incidentId, clock.instant());
        if (claimed.isEmpty()) {
            return repository.findById(incidentId).orElse(existing);
        }
        return execute(claimed.orElseThrow(), signals);
    }

    private Incident execute(Incident running, IncidentSignals signals) {
        Instant started = running.startedAt() == null ? clock.instant() : running.startedAt();
        List<Observation> observations = new ArrayList<>(repository.findObservations(running.id()));
        List<String> missingEvidence = new ArrayList<>();
        List<IncidentSkill> matchedSkills = skillMatcher.match(signals, null);
        String terminalReason = null;
        AgentDecision terminalDecision = null;
        int toolCalls = 0;
        int steps = running.steps().size();

        while (terminalReason == null) {
            Duration elapsed = elapsedSince(started);
            if (elapsed.compareTo(properties.totalTimeout()) >= 0) {
                terminalReason = "total investigation timeout exceeded";
                break;
            }
            if (steps >= properties.maxSteps()) {
                terminalReason = "maximum investigation step count exceeded";
                break;
            }

            InvestigationContext context = new InvestigationContext(running, observations, matchedSkills,
                steps, toolCalls, elapsed);
            Optional<AgentDecision> supplied;
            Future<Optional<AgentDecision>> decisionFuture = null;
            try {
                decisionFuture = toolCallExecutor.submit(
                    () -> decisionProvider.nextDecision(context));
                supplied = decisionFuture.get(
                    Math.max(1L, properties.totalTimeout().minus(elapsed).toNanos()),
                    TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                decisionFuture.cancel(true);
                terminalDecision = failureDecision("Agent decision timeout exceeded");
                terminalReason = "total investigation timeout exceeded";
                break;
            } catch (RuntimeException exception) {
                terminalDecision = failureDecision("Agent decision provider failed");
                terminalReason = "decision provider failure";
                persistStep(running, terminalDecision, steps + 1, terminalReason);
                break;
            } catch (InterruptedException exception) {
                decisionFuture.cancel(true);
                Thread.currentThread().interrupt();
                terminalDecision = failureDecision("Agent decision interrupted");
                terminalReason = "decision provider failure";
                persistStep(running, terminalDecision, steps + 1, terminalReason);
                break;
            } catch (ExecutionException exception) {
                terminalDecision = failureDecision("Agent decision provider failed");
                terminalReason = "decision provider failure";
                persistStep(running, terminalDecision, steps + 1, terminalReason);
                break;
            }
            if (supplied == null || supplied.isEmpty()) {
                terminalReason = "agent provider returned no terminal decision";
                break;
            }
            AgentDecision decision = supplied.orElseThrow();
            steps++;
            String decisionType = decision.decisionType().trim().toUpperCase();
            if (COMPLETE.equals(decisionType)) {
                if (decision.toolset() != null && !decision.toolset().isBlank()) {
                    terminalReason = "COMPLETE decision must not select a toolset";
                    persistStep(running, decision, steps, terminalReason);
                } else {
                    persistStep(running, decision, steps, "investigation completed");
                    IncidentReport report = buildReport(running, observations, missingEvidence,
                        decision.summary());
                    repository.saveReport(report);
                    Incident finished = report.requiresHumanReview()
                        ? running.needsHumanReview(clock.instant()) : running.complete(clock.instant());
                    if (repository.updateIfCurrentStatus(finished, IncidentStatus.RUNNING)) {
                        return finished;
                    }
                    return repository.findById(running.id()).orElse(finished);
                }
            } else if (REVIEW.equals(decisionType)) {
                persistStep(running, decision, steps, "human review requested");
                terminalReason = "agent requested human review";
            } else if (TOOL_CALL.equals(decisionType) || hasToolset(decision)) {
                if (decision.toolset() == null || decision.toolset().isBlank()) {
                    persistStep(running, decision, steps, "toolset is required");
                    terminalReason = "invalid tool decision";
                    continue;
                }
                if (toolCalls >= properties.maxToolCalls()) {
                    persistStep(running, decision, steps, "maximum tool-call count exceeded");
                    terminalReason = "maximum tool-call count exceeded";
                    continue;
                }
                toolCalls++;
                ToolResult result = executeTool(running, decision, started);
                UUID stepId = UUID.randomUUID();
                InvestigationStep step = new InvestigationStep(stepId, running.id(), steps, decision,
                    result.status().name(), clock.instant());
                repository.appendStep(step);
                Observation observation = result.toObservation(UUID.randomUUID(), running.id(), stepId,
                    clock.instant());
                repository.appendObservation(observation);
                observations.add(observation);
                result.missingEvidence().ifPresent(missingEvidence::add);
                if (elapsedSince(started).compareTo(properties.totalTimeout()) >= 0) {
                    terminalReason = "total investigation timeout exceeded";
                }
            } else {
                persistStep(running, decision, steps, "unsupported decision type");
                terminalReason = "unsupported decision type";
            }
        }

        AgentDecision decision = terminalDecision == null
            ? failureDecision(terminalReason == null ? "investigation stopped" : terminalReason)
            : terminalDecision;
        if (terminalDecision == null && steps < properties.maxSteps()) {
            persistStep(running, decision, steps + 1, terminalReason);
        }
        repository.saveReport(buildReport(running, observations, missingEvidence, terminalReason));
        Incident terminal = running.transitionTo(IncidentStatus.NEEDS_HUMAN_REVIEW, clock.instant(),
            terminalReason == null ? "investigation stopped" : terminalReason);
        if (terminalReason != null && terminalReason.contains("provider failure")) {
            terminal = running.fail(terminalReason, clock.instant());
        }
        if (repository.updateIfCurrentStatus(terminal, IncidentStatus.RUNNING)) {
            return terminal;
        }
        return repository.findById(running.id()).orElse(terminal);
    }

    private ToolResult executeTool(Incident incident, AgentDecision decision, Instant started) {
        long remainingNanos = Math.min(properties.toolCallTimeout().toNanos(),
            Math.max(1L, properties.totalTimeout().minus(elapsedSince(started)).toNanos()));
        Future<ToolResult> future = toolCallExecutor.submit(
            () -> toolExecutor.execute(incident, new ToolRequest(decision.toolset())));
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return timeoutResult(decision.toolset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failureResult(decision.toolset(), "tool execution interrupted");
        } catch (ExecutionException exception) {
            return failureResult(decision.toolset(), "tool execution failed");
        }
    }

    private void persistStep(Incident incident, AgentDecision decision, int sequence, String outcome) {
        repository.appendStep(new InvestigationStep(UUID.randomUUID(), incident.id(), sequence, decision,
            outcome == null || outcome.isBlank() ? "investigation stopped" : outcome, clock.instant()));
    }

    private IncidentReport buildReport(Incident incident, List<Observation> observations,
                                       List<String> missingEvidence, String recommendation) {
        List<Evidence> evidence = observations.stream()
            .filter(item -> "SUCCESS".equalsIgnoreCase(item.status().trim()))
            .map(item -> new Evidence(UUID.randomUUID(), incident.id(), item.id(), item.sourceType(),
                item.redactedSummary(), item.provenance(), item.observedFrom(), item.observedTo(),
                item.contentHash(), clock.instant()))
            .toList();
        evidence.forEach(repository::appendEvidence);
        List<Hypothesis> hypotheses = List.of(new Hypothesis(UUID.randomUUID(), incident.id(),
            recommendation == null || recommendation.isBlank() ? "Insufficient evidence" : recommendation,
            evidence.isEmpty() ? 0.0 : 0.5, evidence, clock.instant()));
        List<String> missing = new ArrayList<>(missingEvidence == null ? List.of() : missingEvidence);
        if (evidence.isEmpty() && missing.isEmpty()) {
            missing.add("At least two independent successful Toolsets");
        }
        return IncidentReport.forInvestigation(UUID.randomUUID(), incident.id(), hypotheses, evidence,
            missing,
            recommendation == null ? "Human review required" : recommendation, clock.instant());
    }

    private Duration elapsedSince(Instant started) {
        Duration elapsed = Duration.between(started, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    private static AgentDecision failureDecision(String summary) {
        return new AgentDecision("RUNTIME_FAILURE", summary, null);
    }

    private static boolean hasToolset(AgentDecision decision) {
        return decision.toolset() != null && !decision.toolset().isBlank();
    }

    private static ToolResult timeoutResult(String toolName) {
        return failureResult(toolName, "tool-call timeout exceeded");
    }

    private static ToolResult failureResult(String toolName, String message) {
        var toolset = com.stackwatch.incident.toolset.Toolset.fromConfiguredName(toolName)
            .orElse(com.stackwatch.incident.toolset.Toolset.UNKNOWN);
        String summary = "Toolset call failed: " + message;
        return new ToolResult(toolset, ToolResultStatus.FAILURE, summary, "stackwatch:runtime",
            null, null, hash(toolset + "|FAILURE|" + summary), Optional.of("Missing evidence: " + summary));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}

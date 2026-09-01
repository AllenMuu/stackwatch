package com.stackwatch.incident.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable incident aggregate with a lifecycle and a lightweight trigger/step audit trail. */
public record Incident(UUID id, String applicationName, String environment, String clusterId,
                       IncidentStatus status, List<IncidentTrigger> triggers,
                       List<InvestigationStep> steps, Instant createdAt, Instant updatedAt,
                       Instant startedAt, Instant completedAt, String failureReason) {

    public Incident {
        id = Objects.requireNonNull(id, "id is required");
        applicationName = requireText(applicationName, "applicationName");
        environment = requireText(environment, "environment");
        clusterId = requireText(clusterId, "clusterId");
        status = Objects.requireNonNull(status, "status is required");
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        steps = steps == null ? List.of() : List.copyOf(steps);
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    public static Incident pending(UUID id, String applicationName, String environment, String clusterId,
                                   List<IncidentTrigger> triggers, Instant createdAt) {
        return new Incident(id, applicationName, environment, clusterId, IncidentStatus.PENDING,
            triggers, List.of(), createdAt, createdAt, null, null, null);
    }

    public String activeKey() {
        return applicationName + "|" + environment + "|" + clusterId;
    }

    public boolean sameActiveIdentity(Incident other) {
        return other != null
            && applicationName.equals(other.applicationName)
            && environment.equals(other.environment)
            && clusterId.equals(other.clusterId);
    }

    public boolean isActive() {
        return status.isActive();
    }

    public Incident appendTrigger(IncidentTrigger trigger, Instant now) {
        Objects.requireNonNull(trigger, "trigger is required");
        List<IncidentTrigger> updatedTriggers = new ArrayList<>(triggers);
        updatedTriggers.add(trigger);
        return copy(status, updatedTriggers, steps, now, startedAt, completedAt, failureReason);
    }

    public Incident appendStep(InvestigationStep step, Instant now) {
        Objects.requireNonNull(step, "step is required");
        if (!id.equals(step.incidentId())) {
            throw new IllegalArgumentException("step must belong to incident");
        }
        List<InvestigationStep> updatedSteps = new ArrayList<>(steps);
        updatedSteps.add(step);
        return copy(status, triggers, updatedSteps, now, startedAt, completedAt, failureReason);
    }

    public Incident start(Instant now) {
        return transitionTo(IncidentStatus.RUNNING, now, null);
    }

    public Incident complete(Instant now) {
        return transitionTo(IncidentStatus.COMPLETED, now, null);
    }

    public Incident needsHumanReview(Instant now) {
        return transitionTo(IncidentStatus.NEEDS_HUMAN_REVIEW, now, null);
    }

    public Incident fail(String reason, Instant now) {
        return transitionTo(IncidentStatus.FAILED, now, requireText(reason, "reason"));
    }

    public Incident transitionTo(IncidentStatus target, Instant now, String reason) {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(now, "now is required");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition from " + status + " to " + target);
        }
        Instant nextStartedAt = target == IncidentStatus.RUNNING ? now : startedAt;
        Instant nextCompletedAt = target.isActive() ? null : now;
        return copy(target, triggers, steps, now, nextStartedAt, nextCompletedAt, reason);
    }

    private Incident copy(IncidentStatus nextStatus, List<IncidentTrigger> nextTriggers,
                          List<InvestigationStep> nextSteps, Instant nextUpdatedAt,
                          Instant nextStartedAt, Instant nextCompletedAt, String nextFailureReason) {
        return new Incident(id, applicationName, environment, clusterId, nextStatus, nextTriggers,
            nextSteps, createdAt, nextUpdatedAt, nextStartedAt, nextCompletedAt, nextFailureReason);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

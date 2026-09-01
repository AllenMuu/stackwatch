package com.stackwatch.incident.runtime;

import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.Observation;
import com.stackwatch.incident.skills.IncidentSkill;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded context exposed to an AgentDecisionProvider. */
public record InvestigationContext(Incident incident, List<Observation> observations,
                                   List<IncidentSkill> skills, int stepCount,
                                   int toolCallCount, Duration elapsed) {

    public InvestigationContext {
        incident = Objects.requireNonNull(incident, "incident is required");
        observations = observations == null ? List.of() : List.copyOf(observations);
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (stepCount < 0 || toolCallCount < 0) {
            throw new IllegalArgumentException("investigation counters must not be negative");
        }
        elapsed = Objects.requireNonNull(elapsed, "elapsed is required");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}

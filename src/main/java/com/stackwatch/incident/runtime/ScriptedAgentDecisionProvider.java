package com.stackwatch.incident.runtime;

import com.stackwatch.incident.domain.AgentDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic provider used by fixtures and unit tests. */
public final class ScriptedAgentDecisionProvider implements AgentDecisionProvider {

    private final List<AgentDecision> decisions;
    private final AtomicInteger cursor = new AtomicInteger();

    public ScriptedAgentDecisionProvider(List<AgentDecision> decisions) {
        Objects.requireNonNull(decisions, "decisions are required");
        this.decisions = List.copyOf(new ArrayList<>(decisions));
    }

    @Override
    public Optional<AgentDecision> nextDecision(InvestigationContext context) {
        Objects.requireNonNull(context, "context is required");
        int index = cursor.getAndIncrement();
        return index < decisions.size() ? Optional.of(decisions.get(index)) : Optional.empty();
    }
}

package com.stackwatch.incident.runtime;

import com.stackwatch.incident.domain.AgentDecision;
import java.util.Optional;

/** Supplies one structured investigation decision at a time. */
public interface AgentDecisionProvider {

    /**
     * Returns the next decision, or empty when the provider has no further decision.
     * Implementations must return summaries only; deliberative chain-of-thought is never persisted.
     */
    Optional<AgentDecision> nextDecision(InvestigationContext context);
}

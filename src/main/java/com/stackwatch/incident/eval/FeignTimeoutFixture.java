package com.stackwatch.incident.eval;

import com.stackwatch.incident.domain.AgentDecision;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.toolset.Toolset;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Versioned, provider-free fixture for evaluating a Spring Feign timeout investigation. */
public final class FeignTimeoutFixture {

    public static final String VERSION = "2026-08-31.v1";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-31T01:00:00Z");
    private static final Set<Toolset> REQUIRED_TOOLSETS = Set.of(
        Toolset.LOGS, Toolset.TRACE, Toolset.GIT_DEPLOYMENT);

    private FeignTimeoutFixture() {
    }

    /** Creates a stable fixture identity while retaining a unique domain ID per evaluation. */
    public static Incident incident() {
        IncidentTrigger trigger = new IncidentTrigger(UUID.randomUUID(), "EVALUATION",
            "Feign timeout fixture " + VERSION, OBSERVED_AT);
        return Incident.pending(UUID.randomUUID(), "orders", "prod", "fixture-feign-timeout",
            List.of(trigger), OBSERVED_AT);
    }

    /** Scripted actions are intentionally explicit: no LLM or arbitrary tool selection is involved. */
    public static List<AgentDecision> decisions() {
        return List.of(
            new AgentDecision("TOOL_CALL", "Inspect application timeout logs", Toolset.LOGS.configuredName()),
            new AgentDecision("TOOL_CALL", "Correlate the downstream client trace", Toolset.TRACE.configuredName()),
            new AgentDecision("TOOL_CALL", "Compare deployment timing with the incident window",
                Toolset.GIT_DEPLOYMENT.configuredName()),
            new AgentDecision("COMPLETE", "A downstream Feign read timeout followed a deployment change", null));
    }

    public static Set<Toolset> requiredToolsets() {
        return REQUIRED_TOOLSETS;
    }

    public static Set<String> forbiddenToolNames() {
        return Set.of("shell", "sql", "kubernetes", "remediate", "write");
    }

    public static Instant observedAt() {
        return OBSERVED_AT;
    }
}

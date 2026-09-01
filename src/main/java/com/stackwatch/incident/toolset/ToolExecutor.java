package com.stackwatch.incident.toolset;

import com.stackwatch.incident.domain.Incident;
import java.util.Objects;
import java.util.Optional;

/** Enforces the Deep Path's fixed-name, fixed-scope, read-only Toolset policy. */
public final class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolResultNormalizer normalizer;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, new ToolResultNormalizer());
    }

    ToolExecutor(ToolRegistry registry, ToolResultNormalizer normalizer) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer is required");
    }

    /**
     * Executes a configured Toolset against the Incident's server-owned identity only.
     *
     * <p>A request can never select a query scope, endpoint, or credentials.</p>
     */
    public ToolResult execute(Incident incident, ToolRequest request) {
        ToolScope scope = ToolScope.fromIncident(incident);
        Objects.requireNonNull(request, "request is required");
        Optional<Toolset> selectedToolset = Toolset.fromConfiguredName(request.toolName());
        if (selectedToolset.isEmpty()) {
            return normalizer.rejected(Toolset.UNKNOWN, "unregistered tool name");
        }
        Toolset toolset = selectedToolset.orElseThrow();
        Optional<ToolAdapter> adapter = registry.find(toolset);
        if (adapter.isEmpty()) {
            return normalizer.rejected(toolset, "unregistered tool");
        }
        try {
            return normalizer.success(toolset, adapter.orElseThrow().execute(scope));
        } catch (RuntimeException exception) {
            return normalizer.failure(toolset, exception.getMessage());
        }
    }
}

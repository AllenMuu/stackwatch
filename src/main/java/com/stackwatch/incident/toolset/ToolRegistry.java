package com.stackwatch.incident.toolset;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Fixed registry of deployment-configured read-only adapters. */
public final class ToolRegistry {

    private final Map<Toolset, ToolAdapter> adapters;

    public ToolRegistry(Collection<? extends ToolAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters are required");
        Map<Toolset, ToolAdapter> configured = new LinkedHashMap<>();
        for (ToolAdapter adapter : adapters) {
            ToolAdapter requiredAdapter = Objects.requireNonNull(adapter, "adapter is required");
            Toolset toolset = Objects.requireNonNull(
                requiredAdapter.toolset(), "toolset is required");
            if (toolset == Toolset.UNKNOWN) {
                throw new IllegalArgumentException("unknown Toolset cannot be registered");
            }
            if (configured.putIfAbsent(toolset, requiredAdapter) != null) {
                throw new IllegalArgumentException("duplicate adapter for " + toolset);
            }
        }
        this.adapters = Map.copyOf(configured);
    }

    public Optional<ToolAdapter> find(Toolset toolset) {
        return Optional.ofNullable(adapters.get(toolset));
    }
}

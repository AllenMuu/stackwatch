package com.stackwatch.incident.toolset;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Enforces the Deep Path's fixed-name, fixed-scope, read-only Toolset policy. */
public final class ToolExecutor {

    private static final Pattern FORBIDDEN_SCOPE_CONTENT = Pattern.compile(
        "(?i)(https?://|authorization|password|passwd|pwd|secret|token|api[-_]?key|kubectl|\\boc\\b"
            + "|\\b(?:curl|wget|bash|sh|zsh|powershell)\\b|\\b(?:select|insert|update|delete|drop"
            + "|alter"
            + "|create|grant|revoke|truncate)\\b)" );

    private final ToolRegistry registry;
    private final ToolResultNormalizer normalizer;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, new ToolResultNormalizer());
    }

    ToolExecutor(ToolRegistry registry, ToolResultNormalizer normalizer) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry is required");
        this.normalizer = java.util.Objects.requireNonNull(normalizer, "normalizer is required");
    }

    public ToolResult execute(ToolRequest request) {
        java.util.Objects.requireNonNull(request, "request is required");
        Optional<Toolset> selectedToolset = Toolset.fromConfiguredName(request.toolName());
        if (selectedToolset.isEmpty()) {
            return normalizer.rejected(Toolset.UNKNOWN, "unregistered tool name");
        }
        Toolset toolset = selectedToolset.orElseThrow();
        if (!request.suppliedInputs().isEmpty() || hasForbiddenScopeContent(request.scope())) {
            return normalizer.rejected(toolset, "unsafe caller-controlled operational input");
        }
        Optional<ToolAdapter> adapter = registry.find(toolset);
        if (adapter.isEmpty()) {
            return normalizer.rejected(toolset, "unregistered tool");
        }
        try {
            return normalizer.success(toolset, adapter.orElseThrow().execute(request.scope()));
        } catch (RuntimeException exception) {
            return normalizer.failure(toolset, exception.getMessage());
        }
    }

    private static boolean hasForbiddenScopeContent(ToolScope scope) {
        return containsForbidden(scope.applicationName()) || containsForbidden(scope.environment())
            || containsForbidden(scope.clusterId());
    }

    private static boolean containsForbidden(String value) {
        return FORBIDDEN_SCOPE_CONTENT.matcher(value.toLowerCase(Locale.ROOT)).find();
    }
}

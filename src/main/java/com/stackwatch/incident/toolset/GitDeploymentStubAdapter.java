package com.stackwatch.incident.toolset;

import java.time.Instant;

/** Deterministic local Git/Deployment adapter for tests and the first Feign-timeout fixture. */
public final class GitDeploymentStubAdapter implements ToolAdapter {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Override
    public Toolset toolset() {
        return Toolset.GIT_DEPLOYMENT;
    }

    @Override
    public ToolRawResult execute(ToolScope scope) {
        return new ToolRawResult(
            "Deployment orders-service 2026.08.31.1 preceded the timeout increase",
            "stub:git-deployment",
            OBSERVED_AT.minusSeconds(120), OBSERVED_AT.minusSeconds(90));
    }
}

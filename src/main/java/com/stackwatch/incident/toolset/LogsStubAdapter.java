package com.stackwatch.incident.toolset;

import java.time.Instant;

/** Deterministic local Logs adapter for tests and the first Feign-timeout fixture. */
public final class LogsStubAdapter implements ToolAdapter {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Override
    public Toolset toolset() {
        return Toolset.LOGS;
    }

    @Override
    public ToolRawResult execute(ToolScope scope) {
        return new ToolRawResult(
            "Feign timeout while calling order-service. Authorization: Bearer live-token",
            "stub:logs", OBSERVED_AT.minusSeconds(60), OBSERVED_AT);
    }
}

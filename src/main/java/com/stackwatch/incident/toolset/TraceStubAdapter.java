package com.stackwatch.incident.toolset;

import java.time.Instant;

/** Deterministic local Trace adapter for tests and the first Feign-timeout fixture. */
public final class TraceStubAdapter implements ToolAdapter {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Override
    public Toolset toolset() {
        return Toolset.TRACE;
    }

    @Override
    public ToolRawResult execute(ToolScope scope) {
        return new ToolRawResult(
            "Trace confirms the order-service client span ended with a read timeout", "stub:trace",
            OBSERVED_AT.minusSeconds(30), OBSERVED_AT);
    }
}

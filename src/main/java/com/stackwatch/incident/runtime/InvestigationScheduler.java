package com.stackwatch.incident.runtime;

import com.stackwatch.incident.skills.IncidentSignals;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentMap;

/** Schedules at most one asynchronous worker for a pending/running Incident. */
public final class InvestigationScheduler {

    private final DeepInvestigationRuntime runtime;
    private final Executor executor;
    private final ConcurrentMap<UUID, Boolean> scheduled = new ConcurrentHashMap<>();

    public InvestigationScheduler(DeepInvestigationRuntime runtime, Executor executor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
    }

    /** Returns false when an investigation for this Incident is already queued or running. */
    public boolean schedule(UUID incidentId) {
        return schedule(incidentId, new IncidentSignals("", "", java.util.List.of()));
    }

    /** Returns false when an investigation for this Incident is already queued or running. */
    public boolean schedule(UUID incidentId, IncidentSignals signals) {
        Objects.requireNonNull(incidentId, "incidentId is required");
        Objects.requireNonNull(signals, "signals are required");
        if (scheduled.putIfAbsent(incidentId, Boolean.TRUE) != null) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    runtime.run(incidentId, signals);
                } finally {
                    scheduled.remove(incidentId);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            scheduled.remove(incidentId);
            throw exception;
        }
    }
}

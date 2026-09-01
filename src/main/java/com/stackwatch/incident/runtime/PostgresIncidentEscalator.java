package com.stackwatch.incident.runtime;

import com.stackwatch.domain.AnalysisPath;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ReviewLevel;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.skills.IncidentSignals;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Creates/reuses a durable Incident and hands it to the bounded scheduler. */
public final class PostgresIncidentEscalator implements IncidentEscalator {

    private final IncidentRepository repository;
    private final InvestigationScheduler scheduler;
    private final Executor escalationExecutor;

    public PostgresIncidentEscalator(IncidentRepository repository, InvestigationScheduler scheduler) {
        this(repository, scheduler, Runnable::run);
    }

    public PostgresIncidentEscalator(IncidentRepository repository, InvestigationScheduler scheduler,
                                    Executor escalationExecutor) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
        this.escalationExecutor = Objects.requireNonNull(escalationExecutor, "escalationExecutor is required");
    }

    @Override
    public void escalate(ErrorEvent event, AnalysisResult result) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(result, "result is required");
        if (!qualifies(result)) return;
        escalationExecutor.execute(() -> createAndSchedule(event, result));
    }

    private void createAndSchedule(ErrorEvent event, AnalysisResult result) {
        Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        IncidentTrigger trigger = new IncidentTrigger(UUID.randomUUID(), "FAST_PATH",
            result.analysis() == null ? null : result.analysis().rootCause(), occurredAt);
        var incident = repository.createOrReuse(event.appName(),
            event.env() == null ? "unknown" : event.env(), result.clusterId(), trigger);
        scheduler.schedule(incident.id(), new IncidentSignals(event.exceptionType(),
            event.exceptionMessage(), event.stackTrace()));
    }

    private static boolean qualifies(AnalysisResult result) {
        return result.clusterId() != null && !result.clusterId().isBlank()
            && (result.path() == AnalysisPath.LLM_NEW
                || result.reviewLevel() != ReviewLevel.AUTO_CONFIRMED);
    }
}

package com.stackwatch.incident.runtime;

import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;

/** Best-effort boundary between the synchronous Fast Path and Deep Path. */
public interface IncidentEscalator {

    void escalate(ErrorEvent event, AnalysisResult result);

    IncidentEscalator NOOP = (event, result) -> { };
}

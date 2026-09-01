package com.stackwatch.incident.domain;

/** Lifecycle states for a Deep Path incident. */
public enum IncidentStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    NEEDS_HUMAN_REVIEW,
    FAILED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }

    public boolean canTransitionTo(IncidentStatus target) {
        return switch (this) {
            case PENDING -> target == RUNNING || target == NEEDS_HUMAN_REVIEW || target == FAILED;
            case RUNNING -> target == COMPLETED || target == NEEDS_HUMAN_REVIEW || target == FAILED;
            case COMPLETED, NEEDS_HUMAN_REVIEW, FAILED -> false;
        };
    }
}

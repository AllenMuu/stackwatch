package com.stackwatch.incident.toolset;

/** Outcome of a read-only Toolset call; every outcome can be retained as an Observation. */
public enum ToolResultStatus {
    SUCCESS,
    FAILURE,
    REJECTED
}

package com.stackwatch.history;

/** Result of recording an occurrence; accepted is false for an idempotent duplicate. */
public record RecordOccurrenceResult(ErrorGroup group, boolean accepted) {
    public RecordOccurrenceResult {
        if (group == null) throw new IllegalArgumentException("group is required");
    }
}

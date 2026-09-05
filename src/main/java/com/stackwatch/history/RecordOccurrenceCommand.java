package com.stackwatch.history;

import java.time.Instant;

/** Command to create a group or record one accepted occurrence. */
public record RecordOccurrenceCommand(ErrorGroup group, String eventId, Instant occurredAt) {
    public RecordOccurrenceCommand {
        if (group == null) throw new IllegalArgumentException("group is required");
    }

    public static RecordOccurrenceCommand newGroup(ErrorGroup group, String eventId, Instant occurredAt) {
        return new RecordOccurrenceCommand(group, eventId, occurredAt);
    }

    public static RecordOccurrenceCommand existing(ErrorGroup group, String eventId, Instant occurredAt) {
        return new RecordOccurrenceCommand(group, eventId, occurredAt);
    }
}

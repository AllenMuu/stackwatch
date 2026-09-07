package com.stackwatch.history;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Non-durable implementation used when durable history is disabled. */
@Repository
@ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled",
    havingValue = "false", matchIfMissing = true)
public class InMemoryErrorGroupRepository implements ErrorGroupRepository {
    private final Map<ErrorGroupKey, ErrorGroup> groups = new ConcurrentHashMap<>();
    private final Set<String> acceptedEvents = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<ErrorGroup> findExact(ErrorGroupKey key) {
        return Optional.ofNullable(groups.get(key));
    }

    @Override
    public synchronized RecordOccurrenceResult record(RecordOccurrenceCommand command) {
        ErrorGroup current = groups.get(command.group().key());
        if (hasEventId(command.eventId())
            && !acceptedEvents.add(command.group().key().appName() + "\u0000" + command.eventId())) {
            return new RecordOccurrenceResult(current == null ? command.group() : current, false);
        }
        ErrorGroup next = (current == null ? command.group() : current).acceptedAt(command.occurredAt());
        groups.put(next.key(), next);
        return new RecordOccurrenceResult(next, true);
    }

    private boolean hasEventId(String eventId) {
        return eventId != null && !eventId.isBlank();
    }
}

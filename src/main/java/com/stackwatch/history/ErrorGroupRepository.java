package com.stackwatch.history;

import java.util.Optional;

public interface ErrorGroupRepository {
    Optional<ErrorGroup> findExact(ErrorGroupKey key);

    RecordOccurrenceResult record(RecordOccurrenceCommand command);
}

package com.stackwatch.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.domain.FingerprintVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryErrorGroupRepositoryTest {

    private static final Instant FIRST = Instant.parse("2026-08-31T01:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-08-31T03:00:00Z");

    @Test
    void blankEventIdCountsEveryOccurrence() {
        InMemoryErrorGroupRepository repository = new InMemoryErrorGroupRepository();
        ErrorGroup group = group("blank-event-id");

        RecordOccurrenceResult first = repository.record(
            new RecordOccurrenceCommand(group, "", FIRST));
        RecordOccurrenceResult second = repository.record(
            new RecordOccurrenceCommand(group, "  ", SECOND));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(second.group().occurrenceCount()).isEqualTo(2);
        assertThat(second.group().firstSeen()).isEqualTo(FIRST);
        assertThat(second.group().lastSeen()).isEqualTo(SECOND);
    }

    private static ErrorGroup group(String token) {
        ErrorGroupKey key = new ErrorGroupKey("orders", FingerprintVersion.V2, hash(token));
        return ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(), key),
            new ErrorGroup.GroupFacts(hash("loose-" + token), "OuterException", "CauseException",
                "failed to load order", List.of("app.OrderService.load")),
            null, "cluster-" + token));
    }

    private static String hash(String value) {
        return (value + "0".repeat(64)).substring(0, 64);
    }
}

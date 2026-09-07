package com.stackwatch.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stackwatch.config.ErrorHistoryProperties;
import com.stackwatch.domain.FingerprintVersion;
import java.util.UUID;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ErrorHistoryDisabledStartupTest {
    @Autowired ErrorHistoryProperties properties;
    @Autowired ObjectProvider<DataSource> dataSourceProvider;
    @Autowired ErrorGroupRepository repository;

    @Test
    void startsWithoutErrorHistoryDatasourceByDefault() {
        assertThat(properties.enabled()).isFalse();
        assertThat(dataSourceProvider.getIfAvailable()).isNull();
        assertThat(repository).isInstanceOf(InMemoryErrorGroupRepository.class);
    }

    @Test
    void rejectsBlankExactKeyParts() {
        assertThatThrownBy(() -> new ErrorGroupKey("", FingerprintVersion.V2, "hash"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inMemoryRepositoryRecordsAnOccurrence() {
        ErrorGroupKey key = new ErrorGroupKey("billing", FingerprintVersion.V2, "strict");
        ErrorGroup group = ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(), key),
            new ErrorGroup.GroupFacts("loose", null, null, null, null), null, null));
        RecordOccurrenceCommand command = RecordOccurrenceCommand.newGroup(group, "event-1",
            java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(repository.record(command).group().occurrenceCount()).isEqualTo(1);
    }

    @Test
    void duplicateEventIsIdempotentAndDistinctTimesAreOrdered() {
        InMemoryErrorGroupRepository repo = new InMemoryErrorGroupRepository();
        ErrorGroupKey key = new ErrorGroupKey("billing", FingerprintVersion.V2, "strict-2");
        ErrorGroup group = ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(), key),
            new ErrorGroup.GroupFacts("loose-2", null, null, null, null), null, "cluster-1"));
        repo.record(new RecordOccurrenceCommand(group, "event-2", Instant.parse("2026-01-02T00:00:00Z")));
        repo.record(new RecordOccurrenceCommand(group, "event-2", Instant.parse("2026-01-03T00:00:00Z")));
        repo.record(new RecordOccurrenceCommand(group, "event-3", Instant.parse("2025-12-31T00:00:00Z")));
        ErrorGroup result = repo.findExact(key).orElseThrow();
        assertThat(result.occurrenceCount()).isEqualTo(2);
        assertThat(result.firstSeen()).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
        assertThat(result.lastSeen()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void exactIdentitySeparatesApplicationsAndVersions() {
        InMemoryErrorGroupRepository repo = new InMemoryErrorGroupRepository();
        ErrorGroup v2 = ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(),
                new ErrorGroupKey("billing", FingerprintVersion.V2, "same")),
            new ErrorGroup.GroupFacts("loose-a", null, null, null, null), null, null));
        ErrorGroup v1 = ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(),
                new ErrorGroupKey("billing", FingerprintVersion.V1, "same")),
            new ErrorGroup.GroupFacts("loose-b", null, null, null, null), null, null));
        ErrorGroup otherApp = ErrorGroup.newGroup(new ErrorGroup.ErrorGroupSeed(
            new ErrorGroup.GroupIdentity(UUID.randomUUID(),
                new ErrorGroupKey("orders", FingerprintVersion.V2, "same")),
            new ErrorGroup.GroupFacts("loose-c", null, null, null, null), null, null));
        repo.record(new RecordOccurrenceCommand(v2, null, null));
        repo.record(new RecordOccurrenceCommand(v1, null, null));
        repo.record(new RecordOccurrenceCommand(otherApp, null, null));
        ErrorGroup storedV2 = repo.findExact(v2.key()).orElseThrow();
        ErrorGroup storedV1 = repo.findExact(v1.key()).orElseThrow();
        ErrorGroup storedOtherApp = repo.findExact(otherApp.key()).orElseThrow();
        assertThat(storedV2.id()).isEqualTo(v2.id());
        assertThat(storedV1.id()).isEqualTo(v1.id()).isNotEqualTo(storedV2.id());
        assertThat(storedOtherApp.id()).isEqualTo(otherApp.id())
            .isNotEqualTo(storedV2.id()).isNotEqualTo(storedV1.id());
        assertThat(storedV2.occurrenceCount()).isEqualTo(1);
        assertThat(storedV1.occurrenceCount()).isEqualTo(1);
        assertThat(storedOtherApp.occurrenceCount()).isEqualTo(1);
        assertThat(repo.findExact(new ErrorGroupKey("billing", FingerprintVersion.V2, "unknown")))
            .isEmpty();
    }
}

package com.stackwatch.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stackwatch.config.ErrorHistoryProperties;
import com.stackwatch.domain.FingerprintVersion;
import java.util.UUID;
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
        ErrorGroup group = ErrorGroup.newGroup(UUID.randomUUID(), key, "loose", null, null,
            null, null);
        RecordOccurrenceCommand command = RecordOccurrenceCommand.newGroup(group, "event-1",
            java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(repository.record(command).group().occurrenceCount()).isEqualTo(1);
    }
}

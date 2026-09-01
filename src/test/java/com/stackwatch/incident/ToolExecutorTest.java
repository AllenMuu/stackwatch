package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.Observation;
import com.stackwatch.incident.toolset.GitDeploymentStubAdapter;
import com.stackwatch.incident.toolset.LogsStubAdapter;
import com.stackwatch.incident.toolset.ToolAdapter;
import com.stackwatch.incident.toolset.ToolExecutor;
import com.stackwatch.incident.toolset.ToolRawResult;
import com.stackwatch.incident.toolset.ToolRegistry;
import com.stackwatch.incident.toolset.ToolRequest;
import com.stackwatch.incident.toolset.ToolResult;
import com.stackwatch.incident.toolset.ToolResultStatus;
import com.stackwatch.incident.toolset.ToolScope;
import com.stackwatch.incident.toolset.Toolset;
import com.stackwatch.incident.toolset.TraceStubAdapter;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {

    private static final Incident INCIDENT = incident("orders", "prod", "cluster-42");

    @Test
    void executesOnlyARegisteredConfiguredToolWithTheServerOwnedIncidentScope() {
        ToolExecutor executor = stubExecutor();

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.LOGS));

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.toolset()).isEqualTo(Toolset.LOGS);
        assertThat(result.provenance()).isEqualTo("stub:logs");
        assertThat(result.redactedSummary()).contains("Feign timeout");
        assertThat(result.redactedSummary()).doesNotContain("Bearer live-token");
        assertThat(result.missingEvidence()).isEmpty();
    }

    @Test
    void rejectsUnconfiguredToolNamesAndNeverInvokesAnAdapter() {
        CountingAdapter adapter = new CountingAdapter();
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(adapter)));
        ToolRequest request = new ToolRequest("admin-shell");

        ToolResult result = executor.execute(INCIDENT, request);

        assertThat(result.status()).isEqualTo(ToolResultStatus.REJECTED);
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).contains("unregistered tool"));
        assertThat(adapter.calls).isZero();
    }

    @Test
    void rejectsPathShapedUrlsCredentialsShellSqlAndKubernetesToolNamesBeforeAdapterInvocation() {
        CountingAdapter adapter = new CountingAdapter();
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(adapter)));

        assertRejected(executor, "https://attacker.example/steal");
        assertRejected(executor, "authorization: Bearer very-secret-token");
        assertRejected(executor, "curl https://attacker.example");
        assertRejected(executor, "SELECT * FROM customer");
        assertRejected(executor, "kubectl get secrets --all-namespaces");
        assertRejected(executor, "logs/../../etc/passwd");
        assertThat(adapter.calls).isZero();
    }

    @Test
    void derivesTheAdapterScopeFromTheIncidentAndDoesNotExposeACallerScope() {
        CapturingAdapter adapter = new CapturingAdapter();
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(adapter)));

        executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.LOGS));

        assertThat(adapter.receivedScope).isEqualTo(new ToolScope("orders", "prod", "cluster-42"));
        assertThat(ToolRequest.class.getRecordComponents()).extracting(RecordComponent::getName)
            .containsExactly("toolName");
    }

    @Test
    void turnsAdapterFailuresIntoObservationsAndMissingEvidenceInsteadOfEvidence() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new FailingTraceAdapter())));

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        Observation observation = result.toObservation(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILURE);
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).contains("trace source unavailable"));
        assertThat(observation.status()).isEqualTo("FAILURE");
        assertThat(observation.redactedSummary()).doesNotContain("password=super-secret");
    }

    @Test
    void redactsJsonAndQuotedSecretsFromAdapterErrorsBeforeObservationConversion() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new JsonSecretFailingTraceAdapter())));

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        Observation observation = result.toObservation(
            UUID.randomUUID(), INCIDENT.id(), UUID.randomUUID(),
            Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.redactedSummary()).contains("[REDACTED]");
        assertThat(result.redactedSummary()).doesNotContain(
            "json-token", "quoted-token", "api-key-secret", "quoted-authorization");
        assertThat(observation.redactedSummary()).doesNotContain(
            "json-token", "quoted-token", "api-key-secret", "quoted-authorization");
        assertThat(result.missingEvidence()).hasValueSatisfying(missingEvidence ->
            assertThat(missingEvidence).doesNotContain(
                "json-token", "quoted-token", "api-key-secret", "quoted-authorization"));
    }

    @Test
    void redactsEntireMixedQuotePasswordValuesBeforeObservationConversion() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new MixedQuotePasswordFailingTraceAdapter())));

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        Observation observation = result.toObservation(
            UUID.randomUUID(), INCIDENT.id(), UUID.randomUUID(),
            Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.redactedSummary()).contains(
            "password=\"[REDACTED]\"", "password='[REDACTED]'");
        assertThat(result.redactedSummary()).doesNotContain("abc", "def");
        assertThat(observation.redactedSummary()).doesNotContain("abc", "def");
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).doesNotContain("abc", "def"));
    }

    @Test
    void redactsNewlineContainingQuotedPasswordsBeforeObservationConversion() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new NewlinePasswordFailingTraceAdapter())));

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        Observation observation = result.toObservation(
            UUID.randomUUID(), INCIDENT.id(), UUID.randomUUID(),
            Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.redactedSummary()).contains("password=\"[REDACTED]\"");
        assertThat(result.redactedSummary()).doesNotContain("abc", "def", "xyz");
        assertThat(observation.redactedSummary()).doesNotContain("abc", "def", "xyz");
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).doesNotContain("abc", "def", "xyz"));
    }

    @Test
    void redactsUnterminatedQuotedPasswordsToEndOfInputBeforeObservationConversion() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new UnterminatedPasswordFailingTraceAdapter())));

        ToolResult result = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        Observation observation = result.toObservation(
            UUID.randomUUID(), INCIDENT.id(), UUID.randomUUID(),
            Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(result.redactedSummary()).contains("password=\"[REDACTED]");
        assertThat(result.redactedSummary()).doesNotContain("secret");
        assertThat(observation.redactedSummary()).doesNotContain("secret");
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).doesNotContain("secret"));
    }

    @Test
    void suppliesNormalizedDeterministicResultsForAllStubToolsets() {
        ToolExecutor executor = stubExecutor();

        ToolResult logs = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.LOGS));
        ToolResult trace = executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.TRACE));
        ToolResult deployment = executor.execute(
            INCIDENT, ToolRequest.forToolset(Toolset.GIT_DEPLOYMENT));

        assertThat(logs.contentHash()).matches("[0-9a-f]{64}");
        assertThat(trace.contentHash()).matches("[0-9a-f]{64}");
        assertThat(deployment.contentHash()).matches("[0-9a-f]{64}");
        assertThat(executor.execute(INCIDENT, ToolRequest.forToolset(Toolset.LOGS)))
            .isEqualTo(logs);
    }

    private static ToolExecutor stubExecutor() {
        return new ToolExecutor(new ToolRegistry(List.of(
            new LogsStubAdapter(), new TraceStubAdapter(), new GitDeploymentStubAdapter())));
    }

    private static void assertRejected(ToolExecutor executor, String hostileToolName) {
        ToolResult result = executor.execute(INCIDENT, new ToolRequest(hostileToolName));

        assertThat(result.status()).isEqualTo(ToolResultStatus.REJECTED);
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).contains("unregistered tool"));
    }

    private static Incident incident(String applicationName, String environment, String clusterId) {
        Instant createdAt = Instant.parse("2026-09-01T00:00:00Z");
        return Incident.pending(
            UUID.fromString("7b704f82-a4c8-45c2-ae39-92da0da366a6"), applicationName, environment,
            clusterId, List.of(new IncidentTrigger(
                UUID.fromString("cf13b3cd-3f1b-4548-af71-a8a834dfddaa"), "TEST", "test",
                createdAt)),
            createdAt);
    }

    private static final class FailingTraceAdapter implements ToolAdapter {

        @Override
        public Toolset toolset() {
            return Toolset.TRACE;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            throw new IllegalStateException("trace source unavailable password=super-secret");
        }
    }

    private static final class JsonSecretFailingTraceAdapter implements ToolAdapter {

        @Override
        public Toolset toolset() {
            return Toolset.TRACE;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            throw new IllegalStateException(
                "provider rejected {\"authorization\":\"Bearer json-token\","
                    + "\"token\":\"quoted-token\","
                    + "\"apiKey\":\"api-key-secret\"}; Authorization: \"quoted-authorization\"");
        }
    }

    private static final class CapturingAdapter implements ToolAdapter {

        private ToolScope receivedScope;

        @Override
        public Toolset toolset() {
            return Toolset.LOGS;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            receivedScope = scope;
            return new LogsStubAdapter().execute(scope);
        }
    }

    private static final class MixedQuotePasswordFailingTraceAdapter implements ToolAdapter {

        @Override
        public Toolset toolset() {
            return Toolset.TRACE;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            throw new IllegalStateException(
                "provider rejected password=\"abc'def\" password='abc\"def'");
        }
    }

    private static final class NewlinePasswordFailingTraceAdapter implements ToolAdapter {

        @Override
        public Toolset toolset() {
            return Toolset.TRACE;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            throw new IllegalStateException("provider rejected password=\"abc'def\nxyz\"");
        }
    }

    private static final class UnterminatedPasswordFailingTraceAdapter implements ToolAdapter {

        @Override
        public Toolset toolset() {
            return Toolset.TRACE;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            throw new IllegalStateException("provider rejected password=\"secret");
        }
    }

    private static final class CountingAdapter implements ToolAdapter {

        private int calls;

        @Override
        public Toolset toolset() {
            return Toolset.LOGS;
        }

        @Override
        public ToolRawResult execute(ToolScope scope) {
            calls++;
            return new LogsStubAdapter().execute(scope);
        }
    }
}

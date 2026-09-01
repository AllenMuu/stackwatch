package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stackwatch.incident.domain.Evidence;
import com.stackwatch.incident.domain.Hypothesis;
import com.stackwatch.incident.domain.Incident;
import com.stackwatch.incident.domain.IncidentReport;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.domain.IncidentTrigger;
import com.stackwatch.incident.domain.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentDomainTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T01:00:00Z");

    @Test
    void activeIdentityUsesApplicationEnvironmentAndClusterOnly() {
        Incident pending = pendingIncident("orders", "prod", "cluster-42");
        Incident running = pending.start(CREATED_AT.plusSeconds(1));
        Incident completed = running.complete(CREATED_AT.plusSeconds(2));

        assertThat(pending.activeKey()).isEqualTo("orders|prod|cluster-42");
        assertThat(pending.isActive()).isTrue();
        assertThat(running.isActive()).isTrue();
        assertThat(completed.isActive()).isFalse();
        assertThat(pending.sameActiveIdentity(running)).isTrue();
        assertThat(pending.sameActiveIdentity(
            pendingIncident("orders", "staging", "cluster-42"))).isFalse();
    }

    @Test
    void lifecycleAllowsOnlyPendingToRunningThenTerminalTransitions() {
        Incident pending = pendingIncident("orders", "prod", "cluster-42");

        assertThatIllegalStateException().isThrownBy(() -> pending.complete(CREATED_AT))
            .withMessageContaining("PENDING");

        Incident running = pending.start(CREATED_AT.plusSeconds(1));
        Incident completed = running.complete(CREATED_AT.plusSeconds(2));

        assertThat(running.status()).isEqualTo(IncidentStatus.RUNNING);
        assertThat(running.startedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThat(completed.status()).isEqualTo(IncidentStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
        assertThatIllegalStateException().isThrownBy(() -> completed.start(CREATED_AT.plusSeconds(3)));
    }

    @Test
    void appendingATriggerReturnsANewIncidentAndDefensivelyCopiesCollections() {
        IncidentTrigger first = trigger("FAST_PATH", "first signal", CREATED_AT);
        List<IncidentTrigger> suppliedTriggers = new ArrayList<>(List.of(first));
        Incident pending = Incident.pending(
            UUID.randomUUID(), "orders", "prod", "cluster-42", suppliedTriggers, CREATED_AT);
        suppliedTriggers.clear();

        IncidentTrigger duplicate = trigger("MANUAL", "investigate again", CREATED_AT.plusSeconds(1));
        Incident withDuplicate = pending.appendTrigger(duplicate, CREATED_AT.plusSeconds(1));

        assertThat(pending.triggers()).containsExactly(first);
        assertThat(withDuplicate.triggers()).containsExactly(first, duplicate);
        assertThat(withDuplicate.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThatThrownBy(() -> withDuplicate.triggers().add(duplicate))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void twoIndependentEvidenceSourcesVerifyHypothesisAndCompleteReport() {
        UUID incidentId = UUID.randomUUID();
        List<Evidence> evidence = List.of(
            evidence(incidentId, "LOGS", "timeout started"),
            evidence(incidentId, "TRACE", "downstream timeout"));

        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), incidentId, "Order service timed out after deployment", 0.9, evidence, CREATED_AT);
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), incidentId, List.of(hypothesis), evidence, List.of(),
            "Roll back or repair the downstream deployment", CREATED_AT.plusSeconds(1));

        assertThat(hypothesis.verificationStatus()).isEqualTo(Hypothesis.VerificationStatus.VERIFIED);
        assertThat(hypothesis.needsHumanReview()).isFalse();
        assertThat(report.reviewOutcome()).isEqualTo(IncidentStatus.COMPLETED);
        assertThat(report.requiresHumanReview()).isFalse();
    }

    @Test
    void oneOrNoIndependentSourcesRequireHumanReview() {
        UUID incidentId = UUID.randomUUID();
        Evidence firstLog = evidence(incidentId, "LOGS", "timeout started");
        Evidence secondLog = evidence(incidentId, "LOGS", "retry exhausted");

        Hypothesis provisional = new Hypothesis(
            UUID.randomUUID(), incidentId, "Order service timed out", 0.7,
            List.of(firstLog, secondLog), CREATED_AT);
        Hypothesis unknown = new Hypothesis(
            UUID.randomUUID(), incidentId, "Deployment changed behavior", 0.2, List.of(), CREATED_AT);
        IncidentReport report = IncidentReport.forInvestigation(
            UUID.randomUUID(), incidentId, List.of(provisional, unknown), List.of(firstLog, secondLog),
            List.of("Trace correlation"), "Gather more evidence", CREATED_AT.plusSeconds(1));

        assertThat(provisional.verificationStatus()).isEqualTo(Hypothesis.VerificationStatus.PROVISIONAL);
        assertThat(unknown.verificationStatus()).isEqualTo(Hypothesis.VerificationStatus.UNKNOWN);
        assertThat(report.reviewOutcome()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(report.requiresHumanReview()).isTrue();
    }

    @Test
    void constructionForcesUnknownAndHumanReviewWhenEvidenceIsEmpty() {
        UUID incidentId = UUID.randomUUID();
        Hypothesis unknown = new Hypothesis(
            UUID.randomUUID(), incidentId, "Deployment changed behavior", 0.9, List.of(), CREATED_AT);

        IncidentReport report = new IncidentReport(
            UUID.randomUUID(), incidentId, List.of(unknown), List.of(), List.of("Logs"),
            "Inspect the deployment", IncidentStatus.COMPLETED, CREATED_AT.plusSeconds(1));

        assertThat(unknown.verificationStatus()).isEqualTo(Hypothesis.VerificationStatus.UNKNOWN);
        assertThat(report.reviewOutcome()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
    }

    @Test
    void reportRejectsHypothesisCitationThatIsNotInItsEvidenceSet() {
        UUID incidentId = UUID.randomUUID();
        Evidence logs = evidence(incidentId, "LOGS", "timeout started");
        Evidence trace = evidence(incidentId, "TRACE", "downstream timeout");
        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), incidentId, "Order service timed out", 0.9,
            List.of(logs, trace), CREATED_AT);

        assertThatIllegalArgumentException().isThrownBy(() -> new IncidentReport(
            UUID.randomUUID(), incidentId, List.of(hypothesis), List.of(logs), List.of(),
            "Inspect downstream", IncidentStatus.COMPLETED, CREATED_AT.plusSeconds(1)));
    }

    @Test
    void verifiedEvidenceMayStillBeExplicitlyRoutedToHumanReview() {
        UUID incidentId = UUID.randomUUID();
        Evidence logs = evidence(incidentId, "LOGS", "timeout started");
        Evidence trace = evidence(incidentId, "TRACE", "downstream timeout");
        Hypothesis hypothesis = new Hypothesis(
            UUID.randomUUID(), incidentId, "Order service timed out", 0.9,
            List.of(logs, trace), CREATED_AT);

        IncidentReport report = new IncidentReport(
            UUID.randomUUID(), incidentId, List.of(hypothesis), List.of(logs, trace), List.of(),
            "Inspect downstream", IncidentStatus.NEEDS_HUMAN_REVIEW, CREATED_AT.plusSeconds(1));

        assertThat(report.reviewOutcome()).isEqualTo(IncidentStatus.NEEDS_HUMAN_REVIEW);
        assertThat(report.requiresHumanReview()).isTrue();
    }

    @Test
    void evidenceRejectsFailureAndRejectedObservations() {
        Observation failed = observation("FAILURE");
        Observation rejected = observation("REJECTED");

        assertThatIllegalArgumentException().isThrownBy(
            () -> Evidence.requireEligibleObservation(failed));
        assertThatIllegalArgumentException().isThrownBy(
            () -> Evidence.requireEligibleObservation(rejected));
        assertThatCode(() -> Evidence.requireEligibleObservation(observation("SUCCESS")))
            .doesNotThrowAnyException();
    }

    private static Incident pendingIncident(String application, String environment, String clusterId) {
        return Incident.pending(
            UUID.randomUUID(), application, environment, clusterId,
            List.of(trigger("FAST_PATH", "qualifying cluster", CREATED_AT)), CREATED_AT);
    }

    private static IncidentTrigger trigger(String type, String note, Instant createdAt) {
        return new IncidentTrigger(UUID.randomUUID(), type, note, createdAt);
    }

    private static Evidence evidence(UUID incidentId, String sourceType, String summary) {
        return new Evidence(
            UUID.randomUUID(), incidentId, UUID.randomUUID(), sourceType, summary,
            "incident://" + sourceType.toLowerCase(), CREATED_AT, CREATED_AT,
            sourceType + "-hash", CREATED_AT);
    }

    private static Observation observation(String status) {
        return new Observation(
            UUID.randomUUID(), UUID.randomUUID(), null, "TRACE", status, "Trace unavailable",
            "trace://orders", CREATED_AT, CREATED_AT, "trace-hash", CREATED_AT);
    }
}

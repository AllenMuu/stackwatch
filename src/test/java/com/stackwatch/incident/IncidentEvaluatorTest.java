package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.incident.eval.FeignTimeoutFixture;
import com.stackwatch.incident.eval.IncidentEvaluator;
import com.stackwatch.incident.domain.IncidentStatus;
import com.stackwatch.incident.toolset.Toolset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncidentEvaluatorTest {

    @Test
    void evaluatesVersionedFeignTimeoutWithRequiredEvidenceAndNoForbiddenToolsets() {
        IncidentEvaluator.EvaluationResult result = new IncidentEvaluator().evaluate();

        assertThat(result.passed()).isTrue();
        assertThat(result.fixtureVersion()).isEqualTo(FeignTimeoutFixture.VERSION);
        assertThat(result.invokedToolsets()).containsExactlyInAnyOrder(
            Toolset.LOGS, Toolset.TRACE, Toolset.GIT_DEPLOYMENT);
        assertThat(result.invokedToolsets()).doesNotContain(Toolset.UNKNOWN);
        assertThat(result.evidence()).hasSize(3);
        assertThat(result.report().reviewOutcome()).isEqualTo(IncidentStatus.COMPLETED);
        assertThat(result.report().hypotheses()).singleElement()
            .satisfies(hypothesis -> assertThat(hypothesis.verificationStatus())
                .isEqualTo(com.stackwatch.incident.domain.Hypothesis.VerificationStatus.VERIFIED));
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void fixtureDeclaresOnlyTheThreeReadOnlyToolsets() {
        assertThat(FeignTimeoutFixture.requiredToolsets())
            .isEqualTo(Set.of(Toolset.LOGS, Toolset.TRACE, Toolset.GIT_DEPLOYMENT));
        assertThat(FeignTimeoutFixture.forbiddenToolNames())
            .contains("shell", "sql", "kubernetes", "remediate", "write");
    }
}

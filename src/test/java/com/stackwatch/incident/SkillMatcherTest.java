package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.domain.RootCauseAnalysis;
import com.stackwatch.incident.skills.IncidentSignals;
import com.stackwatch.incident.skills.IncidentSkill;
import com.stackwatch.incident.skills.IncidentSkillLoader;
import com.stackwatch.incident.skills.SkillMatcher;
import com.stackwatch.incident.toolset.Toolset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillMatcherTest {

    @Test
    void loadsTheThreeVersionedJvmSkillsFromResources() {
        List<IncidentSkill> skills = new IncidentSkillLoader().loadDefaults();

        assertThat(skills).extracting(IncidentSkill::id)
            .containsExactly(
                "java/null-pointer",
                "redis/connection-pool-exhaustion",
                "spring/feign-timeout");
        assertThat(skills).allSatisfy(skill -> assertThat(skill.version()).isNotBlank());
        assertThat(skills).filteredOn(skill -> skill.id().equals("spring/feign-timeout"))
            .singleElement()
            .satisfies(skill -> assertThat(skill.toolsets())
                .containsExactly(Toolset.LOGS, Toolset.TRACE, Toolset.GIT_DEPLOYMENT));
    }

    @Test
    void matchesFeignTimeoutDeterministicallyFromSignalsAndFastPathRca() {
        SkillMatcher matcher = new SkillMatcher(new IncidentSkillLoader().loadDefaults());
        IncidentSignals signals = new IncidentSignals(
            "feign.RetryableException", "Read timed out executing GET /orders", List.of());
        RootCauseAnalysis fastPathRca = new RootCauseAnalysis(
            "Downstream order service timeout after deployment", "NETWORK", "HIGH", 0.94,
            "Inspect the order service deployment", List.of("read timed out"), false);

        assertThat(matcher.match(signals, fastPathRca)).map(IncidentSkill::id)
            .containsExactly("spring/feign-timeout");
        assertThat(matcher.select(signals, fastPathRca)).map(IncidentSkill::id)
            .contains("spring/feign-timeout");
    }

    @Test
    void matchesFeignWhenFastPathRcaSuppliesARequiredSignalMissingFromTheException() {
        SkillMatcher matcher = new SkillMatcher(new IncidentSkillLoader().loadDefaults());
        IncidentSignals signals = new IncidentSignals(
            "feign.RetryableException", "Downstream call failed", List.of());
        RootCauseAnalysis fastPathRca = new RootCauseAnalysis(
            "The downstream order service read timeout is increasing", "NETWORK", "HIGH", 0.94,
            "Inspect the downstream deployment", List.of(), false);

        assertThat(matcher.select(signals, fastPathRca)).map(IncidentSkill::id)
            .contains("spring/feign-timeout");
    }

    @Test
    void doesNotSelectASkillWhenTheSignalsDoNotMatchItsConfiguredRequirements() {
        SkillMatcher matcher = new SkillMatcher(new IncidentSkillLoader().loadDefaults());
        IncidentSignals signals = new IncidentSignals(
            "java.lang.IllegalArgumentException", "invalid customer request", List.of());
        RootCauseAnalysis fastPathRca = new RootCauseAnalysis(
            "Customer supplied an invalid field", "VALIDATION", "LOW", 0.8,
            "Validate input", List.of(), false);

        assertThat(matcher.select(signals, fastPathRca)).isEmpty();
    }
}

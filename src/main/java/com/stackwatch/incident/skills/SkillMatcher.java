package com.stackwatch.incident.skills;

import com.stackwatch.domain.RootCauseAnalysis;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Deterministic signal/RCA matcher. It deliberately has no LLM dependency or selection path. */
public final class SkillMatcher {

    private final List<IncidentSkill> skills;

    public SkillMatcher(List<IncidentSkill> skills) {
        Objects.requireNonNull(skills, "skills are required");
        this.skills = skills.stream().sorted(Comparator.comparing(IncidentSkill::id)).toList();
    }

    public List<IncidentSkill> match(IncidentSignals signals, RootCauseAnalysis fastPathRca) {
        Objects.requireNonNull(signals, "signals are required");
        String searchableText = (signals.searchableText() + "\n" + rcaText(fastPathRca))
            .toLowerCase(Locale.ROOT);
        return skills.stream().filter(skill -> skill.requiredSignals().stream()
            .map(signal -> signal.toLowerCase(Locale.ROOT))
            .allMatch(searchableText::contains)).toList();
    }

    public Optional<IncidentSkill> select(IncidentSignals signals, RootCauseAnalysis fastPathRca) {
        return match(signals, fastPathRca).stream().findFirst();
    }

    private static String rcaText(RootCauseAnalysis fastPathRca) {
        if (fastPathRca == null) {
            return "";
        }
        return String.join("\n", nullToEmpty(fastPathRca.rootCause()),
            nullToEmpty(fastPathRca.category()),
            nullToEmpty(fastPathRca.suggestedFix()), String.join("\n", fastPathRca.evidence()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

package com.stackwatch.incident.toolset;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The only read-only investigation toolsets that StackWatch can execute. */
public enum Toolset {
    LOGS("logs"),
    TRACE("trace"),
    GIT_DEPLOYMENT("git-deployment"),
    UNKNOWN("unknown");

    private final String configuredName;

    Toolset(String configuredName) {
        this.configuredName = configuredName;
    }

    public String configuredName() {
        return configuredName;
    }

    /** Resolves only a name listed in the fixed application Toolset configuration. */
    public static Optional<Toolset> fromConfiguredName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(toolset -> toolset != UNKNOWN && toolset.configuredName.equals(normalized))
            .findFirst();
    }
}

package com.stackwatch.preprocess;

import java.util.List;

/** Immutable, deterministic input to V2 fingerprint rendering. */
public record NormalizedError(
    String outerExceptionType,
    String effectiveExceptionType,
    String normalizedMessage,
    String normalizedRootCauseMessage,
    List<String> applicationFrames,
    List<String> rootCauseFrames,
    int causeDepth
) {
    public NormalizedError {
        outerExceptionType = emptyIfNull(outerExceptionType);
        effectiveExceptionType = emptyIfNull(effectiveExceptionType);
        normalizedMessage = emptyIfNull(normalizedMessage);
        normalizedRootCauseMessage = emptyIfNull(normalizedRootCauseMessage);
        applicationFrames = applicationFrames == null ? List.of() : List.copyOf(applicationFrames);
        rootCauseFrames = rootCauseFrames == null ? List.of() : List.copyOf(rootCauseFrames);
    }

    /** Compatibility constructor for callers using grouped value objects. */
    public NormalizedError(Types types, Messages messages, Frames frames, int causeDepth) {
        this(
            types == null ? null : types.outer(),
            types == null ? null : types.effective(),
            messages == null ? null : messages.outer(),
            messages == null ? null : messages.rootCause(),
            frames == null ? null : frames.application(),
            frames == null ? null : frames.rootCause(),
            causeDepth);
    }

    private static String emptyIfNull(String value) { return value == null ? "" : value; }

    public record Types(String outer, String effective) {
    }

    public record Messages(String outer, String rootCause) {
    }

    public record Frames(List<String> application, List<String> rootCause) {
        public Frames {
            application = application == null ? List.of() : List.copyOf(application);
            rootCause = rootCause == null ? List.of() : List.copyOf(rootCause);
        }
    }
}

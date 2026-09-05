package com.stackwatch.preprocess;

import com.stackwatch.config.FingerprintProperties;
import com.stackwatch.domain.StackFrame;
import com.stackwatch.domain.ThrowableInfo;
import java.util.List;

/** Selects and normalizes stable stack-frame evidence for V2 identity. */
public final class StackFrameNormalizer {
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
        "java.", "javax.", "jakarta.", "sun.", "jdk.",
        "org.springframework.", "org.apache.", "com.alibaba.fastjson",
        "io.netty.", "reactor.core."
    );

    private final List<String> applicationPackages;
    private final int topN;

    public StackFrameNormalizer(FingerprintProperties properties, int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be positive: " + topN);
        }
        this.applicationPackages = properties.applicationPackages();
        this.topN = topN;
    }

    public List<String> select(ThrowableInfo outer, ThrowableInfo effective) {
        List<String> selected = applicationFrames(effective);
        if (!selected.isEmpty()) {
            return selected;
        }
        selected = applicationFrames(outer);
        if (!selected.isEmpty()) {
            return selected;
        }
        selected = normalizedFrames(effective);
        if (!selected.isEmpty()) {
            return selected;
        }
        return normalizedFrames(outer);
    }

    public List<String> normalizedFrames(ThrowableInfo throwable) {
        if (throwable == null) {
            return List.of();
        }
        return throwable.stackTrace().stream()
            .map(StackFrame::parse)
            .filter(StackFrameNormalizer::isUsable)
            .limit(topN)
            .map(StackFrame::normalized)
            .toList();
    }

    private List<String> applicationFrames(ThrowableInfo throwable) {
        if (throwable == null) {
            return List.of();
        }
        return throwable.stackTrace().stream()
            .map(StackFrame::parse)
            .filter(StackFrameNormalizer::isUsable)
            .filter(frame -> isApplicationCode(frame.className()))
            .limit(topN)
            .map(StackFrame::normalized)
            .toList();
    }

    private boolean isApplicationCode(String className) {
        if (!applicationPackages.isEmpty()) {
            return applicationPackages.stream()
                .anyMatch(prefix -> isInPackage(className, prefix));
        }
        return FRAMEWORK_PREFIXES.stream().noneMatch(className::startsWith);
    }

    private static boolean isInPackage(String className, String prefix) {
        String normalizedPrefix = prefix.endsWith(".")
            ? prefix.substring(0, prefix.length() - 1)
            : prefix;
        return className.equals(normalizedPrefix) || className.startsWith(normalizedPrefix + ".");
    }

    private static boolean isUsable(StackFrame frame) {
        return frame.className() != null && !frame.className().isBlank();
    }
}

package com.stackwatch.preprocess;

import com.stackwatch.config.FingerprintProperties;
import com.stackwatch.domain.ThrowableInfo;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Resolves the deepest typed non-wrapper node on the primary cause chain. */
public final class CauseResolver {
    private static final int MAX_CAUSE_DEPTH = 32;

    private final List<String> wrapperTypes;

    public CauseResolver(FingerprintProperties properties) {
        this.wrapperTypes = properties.wrapperExceptionTypes();
    }

    public ResolvedCause resolve(ThrowableInfo outer) {
        if (outer == null) {
            ThrowableInfo empty = new ThrowableInfo(null, null, List.of(), null);
            return new ResolvedCause(empty, empty, 0);
        }

        ThrowableInfo deepestTyped = null;
        ThrowableInfo effective = null;
        int deepestTypedDepth = 0;
        int effectiveDepth = 0;
        int depth = 0;
        Set<ThrowableInfo> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ThrowableInfo current = outer;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (hasType(current)) {
                deepestTyped = current;
                deepestTypedDepth = depth;
                if (!isWrapper(current.type())) {
                    effective = current;
                    effectiveDepth = depth;
                }
            }
            current = current.cause();
            depth++;
        }

        if (effective != null) {
            return new ResolvedCause(outer, effective, effectiveDepth);
        }
        if (deepestTyped != null) {
            return new ResolvedCause(outer, deepestTyped, deepestTypedDepth);
        }
        return new ResolvedCause(outer, outer, 0);
    }

    private boolean isWrapper(String type) {
        String simpleType = simpleName(type);
        return wrapperTypes.stream()
            .anyMatch(candidate -> candidate.equals(type) || simpleName(candidate).equals(simpleType));
    }

    private static boolean hasType(ThrowableInfo throwable) {
        return throwable.type() != null && !throwable.type().isBlank();
    }

    private static String simpleName(String type) {
        int separator = type.lastIndexOf('.');
        return separator < 0 ? type : type.substring(separator + 1);
    }

    public record ResolvedCause(ThrowableInfo outer, ThrowableInfo effective, int depth) {
    }
}

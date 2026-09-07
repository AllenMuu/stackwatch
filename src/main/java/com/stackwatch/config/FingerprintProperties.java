package com.stackwatch.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** V2 fingerprint normalization policy under {@code stackwatch.fingerprint.*}. */
@ConfigurationProperties(prefix = "stackwatch.fingerprint")
public record FingerprintProperties(
    List<String> applicationPackages,
    List<String> wrapperExceptionTypes,
    List<String> applicationErrorCodes
) {
    private static final List<String> KNOWN_WRAPPER_TYPES = List.of(
        "java.util.concurrent.CompletionException",
        "java.util.concurrent.ExecutionException",
        "java.lang.reflect.InvocationTargetException",
        "java.lang.reflect.UndeclaredThrowableException"
    );

    public FingerprintProperties {
        applicationPackages = sanitized(applicationPackages);
        wrapperExceptionTypes = withKnownWrappers(wrapperExceptionTypes);
        applicationErrorCodes = sanitized(applicationErrorCodes);
    }

    private static List<String> withKnownWrappers(List<String> configured) {
        LinkedHashSet<String> wrappers = new LinkedHashSet<>(KNOWN_WRAPPER_TYPES);
        wrappers.addAll(sanitized(configured));
        return List.copyOf(wrappers);
    }

    private static List<String> sanitized(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                sanitized.add(value.trim());
            }
        }
        return List.copyOf(new LinkedHashSet<>(sanitized));
    }
}

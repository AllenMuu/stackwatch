package com.stackwatch.history;

import com.stackwatch.domain.FingerprintVersion;

/** Composite exact identity; application and algorithm version are part of the key. */
public record ErrorGroupKey(String appName, FingerprintVersion fingerprintVersion,
                            String strictFingerprint) {
    public ErrorGroupKey {
        if (appName == null || appName.isBlank()) throw new IllegalArgumentException("appName is required");
        if (fingerprintVersion == null) throw new IllegalArgumentException("fingerprintVersion is required");
        if (strictFingerprint == null || strictFingerprint.isBlank()) {
            throw new IllegalArgumentException("strictFingerprint is required");
        }
    }
}

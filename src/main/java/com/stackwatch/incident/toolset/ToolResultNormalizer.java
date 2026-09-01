package com.stackwatch.incident.toolset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** Redacts adapter output before it crosses the Observation persistence boundary. */
final class ToolResultNormalizer {

    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s,;]+" );
    private static final Pattern SECRET = Pattern.compile(
        "(?i)\\b(authorization|password|passwd|pwd|secret|token|api[-_]?key)\\s*[:=]\\s*"
            + "(?:bearer\\s+)?[^\\s,;]+" );

    ToolResult success(Toolset toolset, ToolRawResult rawResult) {
        String summary = redact(rawResult.summary());
        String provenance = redact(rawResult.provenance());
        return result(toolset, ToolResultStatus.SUCCESS, summary, provenance,
            rawResult.observedFrom(),
            rawResult.observedTo(), Optional.empty());
    }

    ToolResult failure(Toolset toolset, String message) {
        String summary = redact("Toolset call failed: " + safeMessage(message));
        return result(toolset, ToolResultStatus.FAILURE, summary, "stackwatch:tool-executor", null,
            null, Optional.of("Missing evidence: " + summary));
    }

    ToolResult rejected(Toolset toolset, String message) {
        String summary = redact("Toolset request rejected: " + safeMessage(message));
        return result(toolset, ToolResultStatus.REJECTED, summary, "stackwatch:tool-policy", null,
            null, Optional.of("Missing evidence: " + summary));
    }

    private ToolResult result(Toolset toolset, ToolResultStatus status, String summary,
                              String provenance, Instant observedFrom, Instant observedTo,
                              Optional<String> missingEvidence) {
        String contentHash = contentHash(
            toolset, status, summary, provenance, observedFrom, observedTo);
        return new ToolResult(
            toolset, status, summary, provenance, observedFrom, observedTo, contentHash,
            missingEvidence);
    }

    private static String redact(String value) {
        String withoutSecrets = SECRET.matcher(value).replaceAll("$1=[REDACTED]");
        return URL.matcher(withoutSecrets).replaceAll("[REDACTED_URL]");
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank()
            ? "adapter did not provide a failure message"
            : message;
    }

    private static String contentHash(Toolset toolset, ToolResultStatus status, String summary,
                                      String provenance, Instant observedFrom, Instant observedTo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String content = toolset.configuredName() + "|" + status + "|" + summary + "|"
                + provenance
                + "|" + observedFrom + "|" + observedTo;
            return HexFormat.of().formatHex(
                digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}

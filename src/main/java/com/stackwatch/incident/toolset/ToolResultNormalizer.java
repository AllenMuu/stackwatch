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

    private static final String SECRET_FIELD =
        "authorization|password|passwd|pwd|secret|token|api(?:[-_]?key)";
    private static final Pattern JSON_STRING_SECRET = Pattern.compile(
        "(?i)(\"(?:" + SECRET_FIELD + ")\"\\s*:\\s*\")(?:\\\\.|[^\"\\\\])*(\")");
    private static final Pattern QUOTED_SECRET = Pattern.compile(
        "(?i)((?:['\"])?(?:" + SECRET_FIELD + ")(?:['\"])?\\s*[:=]\\s*)(['\"])"
            + "(?:\\\\.|(?!\\2).)*\\2");
    private static final Pattern BARE_SECRET = Pattern.compile(
        "(?i)((?<![A-Za-z0-9_])(?:" + SECRET_FIELD + ")\\b\\s*[:=]\\s*)"
            + "(?:bearer\\s+)?[^\\s,;\"'\\]}]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s,;\"'\\]}]+");

    ToolResult success(Toolset toolset, ToolRawResult rawResult) {
        return normalize(new ResultDetails(toolset, ToolResultStatus.SUCCESS, rawResult.summary(),
            rawResult.provenance(), rawResult.observedFrom(), rawResult.observedTo(), false));
    }

    ToolResult failure(Toolset toolset, String message) {
        return normalize(new ResultDetails(toolset, ToolResultStatus.FAILURE,
            "Toolset call failed: " + safeMessage(message), "stackwatch:tool-executor", null, null,
            true));
    }

    ToolResult rejected(Toolset toolset, String message) {
        return normalize(new ResultDetails(toolset, ToolResultStatus.REJECTED,
            "Toolset request rejected: " + safeMessage(message), "stackwatch:tool-policy", null,
            null,
            true));
    }

    private ToolResult normalize(ResultDetails details) {
        String summary = redact(details.summary());
        String provenance = redact(details.provenance());
        ResultDetails redacted = new ResultDetails(
            details.toolset(), details.status(), summary, provenance,
            details.observedFrom(), details.observedTo(), details.requiresMissingEvidence());
        Optional<String> missingEvidence = redacted.requiresMissingEvidence()
            ? Optional.of("Missing evidence: " + redacted.summary())
            : Optional.empty();
        return new ToolResult(
            redacted.toolset(), redacted.status(), redacted.summary(), redacted.provenance(),
            redacted.observedFrom(), redacted.observedTo(), contentHash(redacted),
            missingEvidence);
    }

    private static String redact(String value) {
        String jsonRedacted = JSON_STRING_SECRET.matcher(value).replaceAll("$1[REDACTED]$2");
        String quotedRedacted = QUOTED_SECRET.matcher(jsonRedacted).replaceAll("$1$2[REDACTED]$2");
        String bareRedacted = BARE_SECRET.matcher(quotedRedacted).replaceAll("$1[REDACTED]");
        return URL.matcher(bareRedacted).replaceAll("[REDACTED_URL]");
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank()
            ? "adapter did not provide a failure message"
            : message;
    }

    private static String contentHash(ResultDetails details) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String content = details.toolset().configuredName() + "|" + details.status() + "|"
                + details.summary() + "|" + details.provenance() + "|" + details.observedFrom()
                + "|" + details.observedTo();
            return HexFormat.of().formatHex(
                digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private record ResultDetails(Toolset toolset, ToolResultStatus status, String summary,
                                 String provenance, Instant observedFrom, Instant observedTo,
                                 boolean requiresMissingEvidence) {
    }
}

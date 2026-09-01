package com.stackwatch.incident.toolset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts adapter output before it crosses the Observation persistence boundary. */
final class ToolResultNormalizer {

    private static final String SECRET_FIELD =
        "authorization|password|passwd|pwd|secret|token|api(?:[-_]?key)";
    private static final Pattern JSON_STRING_SECRET = Pattern.compile(
        "(?i)(\"(?:" + SECRET_FIELD + ")\"\\s*:\\s*\")(?:\\\\.|[^\"\\\\])*(\")");
    private static final Pattern QUOTED_SECRET_PREFIX = Pattern.compile(
        "(?i)(?<![A-Za-z0-9_])(?:['\"])?(?:" + SECRET_FIELD
            + ")(?:['\"])?\\s*[:=]\\s*(['\"])");
    private static final Pattern SECRET_ASSIGNMENT_PREFIX = Pattern.compile(
        "(?i)(?<![A-Za-z0-9_])(?:\\\\)*['\"]?(?:" + SECRET_FIELD
            + ")[\\\"']?(?:\\\\)*\\s*[:=]");
    private static final Pattern SECRET_FIELD_NAME = Pattern.compile(
        "(?i)(?:" + SECRET_FIELD + ")");
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
        return URL.matcher(redactAssignments(value)).replaceAll("[REDACTED_URL]");
    }

    /**
     * Fail-closed redaction for nested/escaped and malformed assignments. Once a secret key is
     * found, its value is hidden up to the next secret key (or end of input), so an unterminated
     * value cannot consume a later key and leak its value.
     */
    private static String redactAssignments(String value) {
        Matcher fields = SECRET_FIELD_NAME.matcher(value);
        StringBuilder redacted = new StringBuilder(value.length());
        int from = 0;
        while (fields.find(from)) {
            if ((fields.start() > 0 && isIdentifierCharacter(value.charAt(fields.start() - 1)))
                || (fields.end() < value.length()
                    && isIdentifierCharacter(value.charAt(fields.end())))) {
                from = fields.end();
                continue;
            }
            int delimiter = findAssignmentDelimiter(value, fields.end());
            if (delimiter < 0) {
                from = fields.end();
                continue;
            }

            int valueStart = skipWhitespace(value, delimiter + 1);
            int openingEnd = valueStart;
            while (openingEnd < value.length() && value.charAt(openingEnd) == '\\') {
                openingEnd++;
            }
            char quote = openingEnd < value.length() ? value.charAt(openingEnd) : 0;
            if (quote == '\'' || quote == '"') {
                openingEnd++;
            } else {
                quote = 0;
                openingEnd = valueStart;
            }

            int nextField = findNextSecretAssignment(value, openingEnd);
            int valueEnd = nextField < 0 ? value.length() : nextField;
            boolean closed = false;
            int closingQuote = -1;
            if (quote != 0) {
                for (int index = openingEnd; index < valueEnd; index++) {
                    if (value.charAt(index) != quote) {
                        continue;
                    }
                    int precedingSlashes = 0;
                    for (int cursor = index - 1;
                         cursor >= openingEnd && value.charAt(cursor) == '\\'; cursor--) {
                        precedingSlashes++;
                    }
                    if ((precedingSlashes & 1) == 1) {
                        continue;
                    }
                    if (index + 1 < valueEnd && value.charAt(index + 1) == quote) {
                        index++;
                        continue;
                    }
                    closingQuote = index;
                    valueEnd = index + 1;
                    closed = true;
                    break;
                }
            } else {
                valueEnd = findUnquotedValueEnd(value, valueStart, valueEnd);
            }

            redacted.append(value, from, quote == 0 ? valueStart : openingEnd)
                .append("[REDACTED]");
            if (closed) {
                redacted.append(value, closingQuote, valueEnd);
            }
            from = valueEnd;
        }
        return redacted.append(value, from, value.length()).toString();
    }

    private static int findUnquotedValueEnd(String value, int from, int limit) {
        for (int index = from; index < limit; index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || current == ',' || current == ';'
                || current == '}' || current == ']') {
                return index;
            }
        }
        return limit;
    }

    private static int findAssignmentDelimiter(String value, int from) {
        int index = from;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == ':' || current == '=') {
                return index;
            }
            if (!Character.isWhitespace(current) && current != '\\'
                && current != '\"' && current != '\'') {
                return -1;
            }
            index++;
        }
        return -1;
    }

    private static int findNextSecretAssignment(String value, int from) {
        Matcher fields = SECRET_FIELD_NAME.matcher(value);
        while (fields.find(from)) {
            if ((fields.start() > 0 && isIdentifierCharacter(value.charAt(fields.start() - 1)))
                || (fields.end() < value.length()
                    && isIdentifierCharacter(value.charAt(fields.end())))) {
                from = fields.end();
                continue;
            }
            if (findAssignmentDelimiter(value, fields.end()) >= 0) {
                return fields.start();
            }
            from = fields.end();
        }
        return -1;
    }

    private static String redactEscapedJsonSecrets(String value) {
        for (int index = 0; index < value.length() - 1; index++) {
            if (!startsEscapedQuote(value, index)) {
                continue;
            }
            int keyStart = index + 2;
            int keyEnd = findEscapedQuote(value, keyStart);
            if (keyEnd < 0 || !isSecretField(value.substring(keyStart, keyEnd))) {
                continue;
            }
            int valueStart = skipWhitespace(value, keyEnd + 2);
            if (valueStart >= value.length() || value.charAt(valueStart) != ':') {
                continue;
            }
            valueStart = skipWhitespace(value, valueStart + 1);
            if (startsEscapedQuote(value, valueStart)) {
                return value.substring(0, valueStart + 2) + "[REDACTED]";
            }
        }
        return value;
    }

    private static String redactQuotedSecrets(String value) {
        Matcher matcher = QUOTED_SECRET_PREFIX.matcher(value);
        StringBuilder redacted = new StringBuilder(value.length());
        int from = 0;
        while (matcher.find(from)) {
            int quoteIndex = matcher.start(1);
            char quote = value.charAt(quoteIndex);
            redacted.append(value, from, quoteIndex + 1).append("[REDACTED]");
            int closingQuote = findClosingQuote(value, quoteIndex + 1, quote);
            if (closingQuote < 0) {
                return redacted.toString();
            }
            redacted.append(quote);
            from = closingQuote + 1;
        }
        return redacted.append(value, from, value.length()).toString();
    }

    private static int findClosingQuote(String value, int from, char quote) {
        for (int index = from; index < value.length(); index++) {
            if (isSecretAssignmentAt(value, index)) {
                return -1;
            }
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                index++;
            } else if (current == quote) {
                if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                    index++;
                    continue;
                }
                return index;
            }
        }
        return -1;
    }

    private static boolean isSecretAssignmentAt(String value, int index) {
        if (index > 0 && isIdentifierCharacter(value.charAt(index - 1))) {
            return false;
        }
        return SECRET_ASSIGNMENT_PREFIX.matcher(value)
            .region(index, value.length())
            .lookingAt();
    }

    private static boolean startsEscapedQuote(String value, int index) {
        return index >= 0 && index + 1 < value.length()
            && value.charAt(index) == '\\' && value.charAt(index + 1) == '"';
    }

    private static int findEscapedQuote(String value, int from) {
        for (int index = from; index < value.length() - 1; index++) {
            if (startsEscapedQuote(value, index)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isSecretField(String value) {
        return SECRET_FIELD_NAME.matcher(value).matches();
    }

    private static int skipWhitespace(String value, int index) {
        int position = index;
        while (position < value.length() && Character.isWhitespace(value.charAt(position))) {
            position++;
        }
        return position;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
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

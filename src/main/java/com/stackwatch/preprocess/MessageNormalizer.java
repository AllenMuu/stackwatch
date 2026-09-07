package com.stackwatch.preprocess;

import com.stackwatch.config.FingerprintProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, ordered message normalization policy for V2 fingerprints. */
public final class MessageNormalizer {
    private static final Pattern UUID = Pattern.compile(
        "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
            + "[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![0-9a-f])");
    private static final Pattern IP_ADDRESS = Pattern.compile(
        "(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
            + "(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?![\\d.])");
    private static final Pattern DATE_TIME = Pattern.compile(
        "(?<!\\d)\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
            + "(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?(?!\\d)");
    private static final Pattern DATE = Pattern.compile(
        "(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)");
    private static final Pattern TIME = Pattern.compile(
        "(?<!\\d)\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?!\\d)");
    private static final Pattern LONG_HASH = Pattern.compile(
        "(?i)(?<![0-9a-f])[0-9a-f]{16,}(?![0-9a-f])");
    private static final Pattern SELECTED_QUERY_VALUE = Pattern.compile(
        "(?i)([?&](?:token|access_token|password|secret|session|trace[_-]?id|"
            + "request[_-]?id)=)([^&#\\s]+)");
    private static final Pattern LONG_BUSINESS_ID = Pattern.compile(
        "(?<![\\p{L}\\p{N}])\\d{6,}(?![\\p{L}\\p{N}])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<String> applicationErrorCodes;

    public MessageNormalizer(FingerprintProperties properties) {
        this.applicationErrorCodes = properties.applicationErrorCodes();
    }

    public String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        ProtectedMessage protectedMessage = protectApplicationCodes(message);
        String normalized = UUID.matcher(protectedMessage.value()).replaceAll("<UUID>");
        normalized = IP_ADDRESS.matcher(normalized).replaceAll("<IP>");
        normalized = DATE_TIME.matcher(normalized).replaceAll("<DATETIME>");
        normalized = DATE.matcher(normalized).replaceAll("<DATE>");
        normalized = TIME.matcher(normalized).replaceAll("<TIME>");
        normalized = LONG_HASH.matcher(normalized).replaceAll("<HASH>");
        normalized = replaceSelectedQueryValues(normalized);
        normalized = LONG_BUSINESS_ID.matcher(normalized).replaceAll("<NUM>");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        return protectedMessage.restore(normalized);
    }

    private ProtectedMessage protectApplicationCodes(String message) {
        String protectedValue = message;
        List<String> placeholders = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        for (int index = 0; index < applicationErrorCodes.size(); index++) {
            String code = applicationErrorCodes.get(index);
            String placeholder = "__SW_CODE_" + index + "__";
            protectedValue = protectedValue.replace(code, placeholder);
            placeholders.add(placeholder);
            originals.add(code);
        }
        return new ProtectedMessage(protectedValue, placeholders, originals);
    }

    private static String replaceSelectedQueryValues(String value) {
        Matcher matcher = SELECTED_QUERY_VALUE.matcher(value);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                normalized, Matcher.quoteReplacement(matcher.group(1) + "<QUERY>"));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private record ProtectedMessage(
        String value, List<String> placeholders, List<String> originals) {
        private String restore(String normalized) {
            String restored = normalized;
            for (int index = 0; index < placeholders.size(); index++) {
                restored = restored.replace(placeholders.get(index), originals.get(index));
            }
            return restored;
        }
    }
}

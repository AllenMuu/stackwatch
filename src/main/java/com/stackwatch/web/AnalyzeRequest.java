package com.stackwatch.web;

import com.stackwatch.domain.ThrowableInfo;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析请求 DTO（不可变）。
 * 对外接口：POST /analyze，输入异常信息，输出 LLM 根因。
 */
public record AnalyzeRequest(
    Context context,
    ThrowableInfo legacyException,
    ThrowableInfo exception
) {
    public AnalyzeRequest {
        context = context == null ? new Context(null, null, Map.of()) : context;
    }

    public static AnalyzeRequest legacy(Context context, ThrowableInfo legacyException) {
        return new AnalyzeRequest(context, legacyException, null);
    }

    public static AnalyzeRequest structured(Context context, ThrowableInfo exception) {
        return new AnalyzeRequest(context, null, exception);
    }

    /**
     * 将兼容 HTTP JSON 显式转换为组合请求对象，避免暴露过长的构造参数列表。
     */
    public static AnalyzeRequest fromJson(JsonNode payload) {
        ThrowableInfo exception = throwableInfo(payload.get("exception"), 0);
        ThrowableInfo legacyException = legacyThrowableInfo(payload);
        return new AnalyzeRequest(
            new Context(
                text(payload, "appName"),
                text(payload, "env"),
                mdc(payload.get("mdc"))),
            legacyException,
            exception);
    }

    public String appName() {
        return context.appName();
    }

    public String env() {
        return context.env();
    }

    public String exceptionType() {
        return legacyException == null ? null : legacyException.type();
    }

    public String exceptionMessage() {
        return legacyException == null ? null : legacyException.message();
    }

    public List<String> stackTrace() {
        return legacyException == null ? null : legacyException.stackTrace();
    }

    public Map<String, String> mdc() {
        return context.mdc();
    }

    /**
     * 将旧字段在 HTTP 边界转换为单节点原始异常树。
     */
    public ThrowableInfo exceptionOrLegacy() {
        if (exception != null) {
            return exception;
        }
        return legacyException == null
            ? new ThrowableInfo(null, null, List.of(), null)
            : legacyException;
    }

    /**
     * 新旧异常输入同时出现时没有无歧义的优先级，调用方必须拒绝请求。
     */
    public boolean hasMixedExceptionForms() {
        return exception != null && legacyException != null;
    }

    /**
     * 请求元数据的组合值对象，保持构造参数短小且保留原有 JSON 字段名称。
     */
    public record Context(String appName, String env, Map<String, String> mdc) {
        public Context {
            mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
        }
    }

    private static ThrowableInfo throwableInfo(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth == 32) {
            return null;
        }
        return new ThrowableInfo(
            text(node, "type"),
            text(node, "message"),
            stackTrace(node.get("stackTrace")),
            throwableInfo(node.get("cause"), depth + 1));
    }

    private static ThrowableInfo legacyThrowableInfo(JsonNode payload) {
        if (payload.get("exceptionType") == null
            && payload.get("exceptionMessage") == null
            && payload.get("stackTrace") == null) {
            return null;
        }
        return new ThrowableInfo(
            text(payload, "exceptionType"),
            text(payload, "exceptionMessage"),
            stackTrace(payload.get("stackTrace")),
            null);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> stackTrace(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> frames = new ArrayList<>();
        node.forEach(frame -> frames.add(frame.asString()));
        return frames;
    }

    private static Map<String, String> mdc(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : node.propertyNames()) {
            values.put(key, node.get(key).asString());
        }
        return values;
    }
}

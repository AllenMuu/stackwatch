package com.stackwatch.web;

import com.stackwatch.domain.ThrowableInfo;

import java.util.List;
import java.util.Map;

/**
 * 分析请求 DTO（不可变）。
 * 对外接口：POST /analyze，输入异常信息，输出 LLM 根因。
 */
public record AnalyzeRequest(
    String appName,
    String env,
    String exceptionType,
    String exceptionMessage,
    List<String> stackTrace,
    ThrowableInfo exception,
    Map<String, String> mdc
) {
    public AnalyzeRequest {
        stackTrace = stackTrace == null ? null : List.copyOf(stackTrace);
        mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }

    /**
     * 兼容旧 HTTP 请求构造方式。
     */
    public AnalyzeRequest(
        String appName,
        String env,
        String exceptionType,
        String exceptionMessage,
        List<String> stackTrace,
        Map<String, String> mdc
    ) {
        this(appName, env, exceptionType, exceptionMessage, stackTrace, null, mdc);
    }

    /**
     * 将旧字段在 HTTP 边界转换为单节点原始异常树。
     */
    public ThrowableInfo exceptionOrLegacy() {
        if (exception != null) {
            return exception;
        }
        return new ThrowableInfo(exceptionType, exceptionMessage, stackTrace, null);
    }

    /**
     * 新旧异常输入同时出现时没有无歧义的优先级，调用方必须拒绝请求。
     */
    public boolean hasMixedExceptionForms() {
        return exception != null
            && (exceptionType != null || exceptionMessage != null || stackTrace != null);
    }
}

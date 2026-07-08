package com.stackwatch.web;

import java.util.List;
import java.util.Map;

/**
 * 分析请求 DTO（不可变）。
 * 对应简历接口：POST /analyze，输入异常信息，输出 LLM 根因。
 */
public record AnalyzeRequest(
    String appName,
    String env,
    String exceptionType,
    String exceptionMessage,
    List<String> stackTrace,
    Map<String, String> mdc
) {
    public AnalyzeRequest {
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
        mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }
}

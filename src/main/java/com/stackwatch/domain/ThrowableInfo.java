package com.stackwatch.domain;

import java.util.List;

/**
 * 原始异常信息：保留主 cause 链中的类型、消息和堆栈，不在采集阶段执行归一化。
 */
public record ThrowableInfo(
    String type,
    String message,
    List<String> stackTrace,
    ThrowableInfo cause
) {
    public ThrowableInfo {
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }
}

package com.stackwatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 错误事件：①采集层产出，②预处理层消费。
 * 不可变，遵循全局 immutability 原则。
 */
public record ErrorEvent(
    String eventId,
    String appName,
    String env,
    Instant occurredAt,
    ThrowableInfo exception,
    Map<String, String> mdc
) {
    public ErrorEvent {
        exception = exception == null ? new ThrowableInfo(null, null, List.of(), null) : exception;
        mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }

    /**
     * 兼容旧调用方的外层异常类型访问方式。
     */
    public String exceptionType() {
        return exception.type();
    }

    /**
     * 兼容旧调用方的外层异常消息访问方式。
     */
    public String exceptionMessage() {
        return exception.message();
    }

    /**
     * 兼容旧调用方的外层堆栈访问方式。
     */
    public List<String> stackTrace() {
        return exception.stackTrace();
    }

    /**
     * 兼容旧调用方按拆解字段创建事件的方式。
     */
    public ErrorEvent(
        String eventId,
        String appName,
        String env,
        Instant occurredAt,
        String exceptionType,
        String exceptionMessage,
        List<String> stackTrace,
        Map<String, String> mdc
    ) {
        this(eventId, appName, env, occurredAt,
            new ThrowableInfo(exceptionType, exceptionMessage, stackTrace, null), mdc);
    }
}

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
    String exceptionType,
    String exceptionMessage,
    List<String> stackTrace,
    Map<String, String> mdc
) {
    public ErrorEvent {
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
        mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }
}

package com.stackwatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 错误事件：①采集层产出，②预处理层消费。
 * 不可变，遵循全局 immutability 原则。
 */
public record ErrorEvent(
    Context context,
    ThrowableInfo exception
) {
    public ErrorEvent {
        context = context == null ? Context.empty() : context;
        exception = exception == null ? new ThrowableInfo(null, null, List.of(), null) : exception;
    }

    public String eventId() {
        return context.identity().eventId();
    }

    public String appName() {
        return context.identity().appName();
    }

    public String env() {
        return context.identity().env();
    }

    public Instant occurredAt() {
        return context.occurredAt();
    }

    public Map<String, String> mdc() {
        return context.mdc();
    }

    public String exceptionType() {
        return exception.type();
    }

    public String exceptionMessage() {
        return exception.message();
    }

    public List<String> stackTrace() {
        return exception.stackTrace();
    }

    /**
     * 事件的采集上下文，避免为原始事件构造传递过长的参数列表。
     */
    public record Context(Identity identity, Instant occurredAt, Map<String, String> mdc) {
        public Context {
            identity = identity == null ? new Identity(null, null, null) : identity;
            mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
        }

        private static Context empty() {
            return new Context(null, null, Map.of());
        }
    }

    /**
     * 可稳定标识事件来源的字段。
     */
    public record Identity(String eventId, String appName, String env) {
    }
}

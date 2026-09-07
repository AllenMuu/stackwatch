package com.stackwatch.collector;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.stackwatch.analyzer.ErrorAnalyzer;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ThrowableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 错误事件采集器：①采集层核心。
 *
 * 职责：把上游（Logback Appender / HTTP /collect / 代码直调）传入的异常，
 * 规范化构造为不可变 {@link ErrorEvent}，并立即触发 ③分析层
 * {@link ErrorAnalyzer#analyze(ErrorEvent)}。
 *
 * 数据流：①采集层 -> ②预处理层（指纹） -> ③分析层（L1/L2/L3 级联）。
 *
 * 两个 collect 重载的分工：
 * - {@link #collect(String, String, Throwable, Map)}：面向 HTTP/代码直调，
 *   从 Throwable 实例提取完整主 cause 链。
 * - {@link #collect(String, String, ThrowableInfo, Map)}：面向 HTTP 和 Logback
 *   Appender，保留已提取的原始异常树，避免将 IThrowableProxy 回填为 Throwable。
 *   两者最终都走 {@link #buildAndAnalyze}，遵循 DRY。
 */
@Service
public class ErrorEventCollector {

    private static final Logger log = LoggerFactory.getLogger(ErrorEventCollector.class);
    private static final int MAX_CAUSE_DEPTH = 32;

    private final ErrorAnalyzer errorAnalyzer;

    public ErrorEventCollector(ErrorAnalyzer errorAnalyzer) {
        this.errorAnalyzer = errorAnalyzer;
    }

    /**
     * 从 Throwable 采集并触发根因分析。
     *
     * @param appName 应用名（来源：MDC / 调用方）
     * @param env     环境（dev/staging/prod）
     * @param t       异常本体；null 时构造空类型事件（防御性兜底，不应常态发生）
     * @param mdc     MDC 上下文（traceId/userId 等），可为 null
     * @return 分析结果（含指纹、簇 ID、根因）
     */
    public AnalysisResult collect(String appName, String env, Throwable t, Map<String, String> mdc) {
        return collect(appName, env, toThrowableInfo(t), mdc);
    }

    /**
     * 从原始异常树采集并触发根因分析（HTTP 与 Logback Appender 路径）。
     *
     * @param exception 原始异常树，可为 null
     */
    public AnalysisResult collect(
        String appName, String env, ThrowableInfo exception, Map<String, String> mdc) {
        return buildAndAnalyze(appName, env, exception, mdc);
    }

    static ThrowableInfo toThrowableInfo(IThrowableProxy source) {
        return fromThrowableProxy(source, new IdentityHashMap<>(), 0);
    }

    private static ThrowableInfo toThrowableInfo(Throwable source) {
        return fromThrowable(source, new IdentityHashMap<>(), 0);
    }

    private static ThrowableInfo fromThrowable(
        Throwable source, IdentityHashMap<Throwable, Boolean> seen, int depth) {
        if (source == null || depth == MAX_CAUSE_DEPTH || seen.put(source, Boolean.TRUE) != null) {
            return null;
        }
        List<String> stackTrace = Arrays.stream(source.getStackTrace())
            .map(Object::toString)
            .toList();
        return new ThrowableInfo(
            source.getClass().getName(), source.getMessage(), stackTrace,
            fromThrowable(source.getCause(), seen, depth + 1));
    }

    private static ThrowableInfo fromThrowableProxy(
        IThrowableProxy source, IdentityHashMap<IThrowableProxy, Boolean> seen, int depth) {
        if (source == null || depth == MAX_CAUSE_DEPTH || seen.put(source, Boolean.TRUE) != null) {
            return null;
        }
        return new ThrowableInfo(
            source.getClassName(), source.getMessage(), extractStackTrace(source),
            fromThrowableProxy(source.getCause(), seen, depth + 1));
    }

    private static List<String> extractStackTrace(IThrowableProxy source) {
        StackTraceElementProxy[] proxies = source.getStackTraceElementProxyArray();
        if (proxies == null || proxies.length == 0) {
            return List.of();
        }
        return Arrays.stream(proxies)
            .map(StackTraceElementProxy::getStackTraceElement)
            .map(Object::toString)
            .toList();
    }

    private AnalysisResult buildAndAnalyze(
        String appName, String env, ThrowableInfo exception, Map<String, String> mdc) {
        ErrorEvent event = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity(UUID.randomUUID().toString(), appName, env),
                Instant.now(),
                mdc),
            exception
        );
        log.debug("Collected error event: id={} type={} app={}",
            event.eventId(), event.exceptionType(), event.appName());
        return errorAnalyzer.analyze(event);
    }
}

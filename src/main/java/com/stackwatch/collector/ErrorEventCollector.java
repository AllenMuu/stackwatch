package com.stackwatch.collector;

import com.stackwatch.analyzer.ErrorAnalyzer;
import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   从 Throwable 实例提取 exceptionType/exceptionMessage/stackTrace。
 * - {@link #collect(String, String, String, String, List, Map)}：面向 Logback
 *   Appender，直接传字段，避免把 IThrowableProxy 回填成 Throwable 时
 *   丢失原始异常类名（getClass().getName() 会返回包装类，而非 NPE 等）。
 *   两者最终都走 {@link #buildAndAnalyze}，遵循 DRY。
 */
@Service
public class ErrorEventCollector {

    private static final Logger log = LoggerFactory.getLogger(ErrorEventCollector.class);

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
        String exceptionType = t == null ? null : t.getClass().getName();
        String exceptionMessage = t == null ? null : t.getMessage();
        List<String> stackTrace = t == null
            ? List.of()
            : Arrays.stream(t.getStackTrace()).map(Objects::toString).toList();
        return collect(appName, env, exceptionType, exceptionMessage, stackTrace, mdc);
    }

    /**
     * 从原始字段采集并触发根因分析（Logback Appender 路径）。
     *
     * @param exceptionType   异常全限定类名，如 {@code java.lang.NullPointerException}
     * @param exceptionMessage 异常消息，可为 null
     * @param stackTrace      规范化堆栈帧列表，可为 null/empty
     */
    public AnalysisResult collect(String appName, String env,
                                  String exceptionType, String exceptionMessage,
                                  List<String> stackTrace, Map<String, String> mdc) {
        return buildAndAnalyze(appName, env, exceptionType, exceptionMessage, stackTrace, mdc);
    }

    private AnalysisResult buildAndAnalyze(String appName, String env,
                                           String exceptionType, String exceptionMessage,
                                           List<String> stackTrace, Map<String, String> mdc) {
        ErrorEvent event = new ErrorEvent(
            UUID.randomUUID().toString(),
            appName,
            env,
            Instant.now(),
            exceptionType,
            exceptionMessage,
            stackTrace,
            mdc
        );
        log.debug("Collected error event: id={} type={} app={}",
            event.eventId(), event.exceptionType(), event.appName());
        return errorAnalyzer.analyze(event);
    }
}

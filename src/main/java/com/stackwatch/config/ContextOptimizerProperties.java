package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文优化器配置：stackwatch.context-optimizer.*
 *
 * <p>驱动 {@link com.stackwatch.analyzer.ContextOptimizer} 对 Prompt 入参与 @Tool 返回值的截断上限。
 * 设计时只暴露"体积不可控字段"的截断阈值：异常消息、MDC、工具结果。
 * stackFrames 已由 {@link AnalysisProperties#fingerprintTopN()} Top-N 精简，
 * historicalSamples / antiPatterns 已由条数硬上限控制，不在此重复设阈值。
 *
 * <p>阈值非法（&le;0）时回退默认值，保持零配置可用。
 */
@ConfigurationProperties(prefix = "stackwatch.context-optimizer")
public record ContextOptimizerProperties(
    int maxExceptionMessageLength,
    int maxMdcLength,
    int maxToolResultLength
) {
    public ContextOptimizerProperties {
        if (maxExceptionMessageLength <= 0) {
            maxExceptionMessageLength = 2000;
        }
        if (maxMdcLength <= 0) {
            maxMdcLength = 1000;
        }
        if (maxToolResultLength <= 0) {
            maxToolResultLength = 4000;
        }
    }
}

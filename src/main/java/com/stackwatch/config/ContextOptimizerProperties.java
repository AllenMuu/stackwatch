package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文优化器配置：stackwatch.context-optimizer.*
 *
 * <p>驱动 {@link com.stackwatch.analyzer.ContextOptimizer} 对 Prompt 入参与 @Tool 返回值的
 * DoS 兜底天花板。{@code exceptionMessage} / {@code mdc} / @Tool 返回值三字段共用此值。
 *
 * <p><b>定位是 DoS backstop，不是预算管控</b>：默认模型 qwen-plus 上下文 ~128k tokens，
 * 整个 prompt 仅数 k tokens，紧截断反而会砍掉承载根因的字段（异常 message 尾部常是
 * SQL Detail / 响应体 error_description 等具体根因）。故默认值很高（65536），正常输入永远
 * 触不到，只在病态输入（如外部 {@code /collect} 塞入 5MB message）时兜底防爆窗口。
 * 切小窗口模型时由部署方显式调小此值。
 *
 * <p>阈值非法（&le;0）时回退默认值，保持零配置可用。
 */
@ConfigurationProperties(prefix = "stackwatch.context-optimizer")
public record ContextOptimizerProperties(
    int maxFieldLength
) {
    public ContextOptimizerProperties {
        if (maxFieldLength <= 0) {
            maxFieldLength = 65536;
        }
    }
}

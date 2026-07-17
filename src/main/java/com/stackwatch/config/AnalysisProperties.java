package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析层配置：stackwatch.analysis.*
 *
 * <p>置信度双阈值驱动 {@link com.stackwatch.domain.ReviewLevel} 三档门控：
 * <ul>
 *   <li>{@code confidence >= confidenceHighThreshold} -> AUTO_CONFIRMED；</li>
 *   <li>{@code confidenceThreshold <= confidence < confidenceHighThreshold} -> NEEDS_CONFIRMATION；</li>
 *   <li>{@code confidence < confidenceThreshold} / 无证据 / LLM 失败 -> NEEDS_HUMAN_REVIEW。</li>
 * </ul>
 * 若高阈值配置异常（&le; 兜底阈值），高阈值回退到兜底阈值，三档退化为二元（保持向后兼容）。
 */
@ConfigurationProperties(prefix = "stackwatch.analysis")
public record AnalysisProperties(
    double similarityThreshold,
    double confidenceThreshold,
    double confidenceHighThreshold,
    int fingerprintTopN
) {
    public AnalysisProperties {
        if (similarityThreshold <= 0 || similarityThreshold > 1) {
            similarityThreshold = 0.92;
        }
        if (confidenceThreshold <= 0 || confidenceThreshold > 1) {
            confidenceThreshold = 0.6;
        }
        if (confidenceHighThreshold <= 0 || confidenceHighThreshold > 1) {
            confidenceHighThreshold = 0.9;
        }
        if (confidenceHighThreshold <= confidenceThreshold) {
            confidenceHighThreshold = confidenceThreshold;
        }
    }
}

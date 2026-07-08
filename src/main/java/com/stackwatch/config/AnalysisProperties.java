package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析层配置：stackwatch.analysis.*
 */
@ConfigurationProperties(prefix = "stackwatch.analysis")
public record AnalysisProperties(
    double similarityThreshold,
    double confidenceThreshold,
    int fingerprintTopN
) {
    public AnalysisProperties {
        if (similarityThreshold <= 0 || similarityThreshold > 1) {
            similarityThreshold = 0.92;
        }
        if (confidenceThreshold <= 0 || confidenceThreshold > 1) {
            confidenceThreshold = 0.6;
        }
        if (fingerprintTopN <= 0) {
            fingerprintTopN = 5;
        }
    }
}

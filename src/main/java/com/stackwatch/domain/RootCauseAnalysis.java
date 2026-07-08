package com.stackwatch.domain;

import java.util.List;

/**
 * LLM 根因分析结果（结构化输出，对应 JSON Schema）。
 * 对应 B1 设计：③分析层 L3 LLM 产出。
 */
public record RootCauseAnalysis(
    String rootCause,
    String category,
    String severity,
    double confidence,
    String suggestedFix,
    List<String> evidence,
    boolean needHumanReview
) {
    public RootCauseAnalysis {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 置信度兜底后重建（保持不可变）。 */
    public RootCauseAnalysis withReviewFlag(boolean needReview) {
        return new RootCauseAnalysis(
            rootCause, category, severity, confidence,
            suggestedFix, evidence, needReview
        );
    }
}

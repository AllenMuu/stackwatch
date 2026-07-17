package com.stackwatch.domain;

/**
 * 分析结果：ErrorAnalyzer 的返回值。
 *
 * <p>携带 {@link ReviewLevel} 复核级别，供下游（飞书通知卡片类型、度量分布）区分对待：
 * L1/L2 复用历史根因时由 {@link ReviewLevel#fromReviewFlag(boolean)} 近似推断；
 * L3 新分析时由 {@code ErrorAnalyzer.classifyReview} 基于置信度 + 证据三档计算。
 */
public record AnalysisResult(
    String fingerprintHash,
    String clusterId,
    RootCauseAnalysis analysis,
    AnalysisPath path,
    ReviewLevel reviewLevel
) {
    public static AnalysisResult cacheHit(String fingerprintHash, RootCauseAnalysis analysis) {
        return new AnalysisResult(fingerprintHash, null, analysis, AnalysisPath.CACHE_HIT,
            ReviewLevel.fromReviewFlag(analysis.needHumanReview()));
    }

    public static AnalysisResult vectorMerged(String fingerprintHash, String clusterId, RootCauseAnalysis analysis) {
        return new AnalysisResult(fingerprintHash, clusterId, analysis, AnalysisPath.VECTOR_MERGED,
            ReviewLevel.fromReviewFlag(analysis.needHumanReview()));
    }

    public static AnalysisResult llmNew(String fingerprintHash, String clusterId,
                                        RootCauseAnalysis analysis, ReviewLevel reviewLevel) {
        return new AnalysisResult(fingerprintHash, clusterId, analysis, AnalysisPath.LLM_NEW, reviewLevel);
    }
}

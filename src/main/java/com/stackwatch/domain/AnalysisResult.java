package com.stackwatch.domain;

/**
 * 分析结果：ErrorAnalyzer 的返回值。
 */
public record AnalysisResult(
    String fingerprintHash,
    String clusterId,
    RootCauseAnalysis analysis,
    AnalysisPath path
) {
    public static AnalysisResult cacheHit(String fingerprintHash, RootCauseAnalysis analysis) {
        return new AnalysisResult(fingerprintHash, null, analysis, AnalysisPath.CACHE_HIT);
    }

    public static AnalysisResult vectorMerged(String fingerprintHash, String clusterId, RootCauseAnalysis analysis) {
        return new AnalysisResult(fingerprintHash, clusterId, analysis, AnalysisPath.VECTOR_MERGED);
    }

    public static AnalysisResult llmNew(String fingerprintHash, String clusterId, RootCauseAnalysis analysis) {
        return new AnalysisResult(fingerprintHash, clusterId, analysis, AnalysisPath.LLM_NEW);
    }
}

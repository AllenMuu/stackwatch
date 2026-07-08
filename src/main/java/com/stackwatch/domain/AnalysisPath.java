package com.stackwatch.domain;

/**
 * 分析路径：区分三种归并来源，用于埋点统计成本故事。
 * 面试讲点："95% 走缓存、4% 走向量归并、只有 1% 真调 LLM"。
 */
public enum AnalysisPath {
    CACHE_HIT,
    VECTOR_MERGED,
    LLM_NEW
}

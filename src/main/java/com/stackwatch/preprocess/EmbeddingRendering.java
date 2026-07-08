package com.stackwatch.preprocess;

/**
 * Embedding 文本渲染策略（借鉴 PostHog rendering 元数据）。
 * 记录 embedding 基于什么文本策略生成，便于 A/B 不同策略对比聚合质量。
 */
public enum EmbeddingRendering {
    /** 仅规范化栈帧 + 异常类型。 */
    PLAIN,
    /** 含异常消息（消息含变量，稳定性弱，但语义更丰富）。 */
    WITH_ERROR_MESSAGE
}

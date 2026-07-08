package com.stackwatch.preprocess;

import com.stackwatch.domain.ErrorEvent;

/**
 * Embedding 服务：L2 向量归并的输入。
 * 对应 B1 设计：②预处理层 -> ③分析层 L2。
 *
 * MVP 阶段：embedding 接口已定义，但 L2 未接入向量库（PgVector 待解锁）。
 * {@link #embed(ErrorEvent)} 返回 null，{@code ErrorAnalyzer} 检测到 null
 * 即跳过 L2、直接走 L3 LLM。
 *
 * 解锁 L2 时：注入 {@code EmbeddingModel}，{@code embed} 返回真实向量。
 *
 * 修正点：{@link EmbeddingRendering} 元数据记录渲染策略（借鉴 PostHog）。
 */
public class EmbeddingService {

    private final Fingerprinter fingerprinter;
    private final EmbeddingRendering rendering;

    public EmbeddingService(Fingerprinter fingerprinter, EmbeddingRendering rendering) {
        this.fingerprinter = fingerprinter;
        this.rendering = rendering;
    }

    /**
     * 生成 embedding。L2 未启用时返回 null。
     */
    public float[] embed(ErrorEvent event) {
        // TODO(L2 解锁): 接入 EmbeddingModel，对 buildEmbeddingText 结果生成向量。
        return null;
    }

    public String buildEmbeddingText(ErrorEvent event) {
        return fingerprinter.buildEmbeddingText(event, rendering);
    }

    public EmbeddingRendering rendering() {
        return rendering;
    }
}

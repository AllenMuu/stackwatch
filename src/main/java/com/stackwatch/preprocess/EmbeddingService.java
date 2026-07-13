package com.stackwatch.preprocess;

import com.stackwatch.domain.ErrorEvent;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Embedding 服务：L2 向量归并的输入。
 * 对应 B1 设计：②预处理层 -> ③分析层 L2。
 *
 * L2 未启用（EmbeddingModel bean 不存在）时 {@link #embed(ErrorEvent)} 返回 null，
 * {@code ErrorAnalyzer} 检测到 null 即跳过 L2、直接走 L3 LLM。
 *
 * 解锁 L2 时：配置 spring.ai.openai.embedding -> EmbeddingModel bean 创建 -> embed 返回真实向量。
 *
 * 修正点：{@link EmbeddingRendering} 元数据记录渲染策略（借鉴 PostHog）。
 */
public class EmbeddingService {

    private final Fingerprinter fingerprinter;
    private final EmbeddingRendering rendering;
    private final EmbeddingModel embeddingModel;

    public EmbeddingService(Fingerprinter fingerprinter, EmbeddingRendering rendering,
                            EmbeddingModel embeddingModel) {
        this.fingerprinter = fingerprinter;
        this.rendering = rendering;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 生成 embedding。EmbeddingModel 未注入（L2 关闭）或调用失败时返回 null，降级走 L3。
     */
    public float[] embed(ErrorEvent event) {
        if (embeddingModel == null) {
            return null;
        }
        try {
            return embeddingModel.embed(buildEmbeddingText(event));
        } catch (Exception e) {
            // embedding 失败不阻塞主流程，降级走 L3
            return null;
        }
    }

    public String buildEmbeddingText(ErrorEvent event) {
        return fingerprinter.buildEmbeddingText(event, rendering);
    }

    public EmbeddingRendering rendering() {
        return rendering;
    }
}

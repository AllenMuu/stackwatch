package com.stackwatch.analyzer;

import com.stackwatch.domain.ErrorCluster;

import java.util.Optional;

/**
 * 错误簇仓储：L2 向量归并的查询/持久化。
 * 对应 B1 设计：③分析层 L2。
 *
 * MVP：{@link InMemoryClusterRepository} 空实现（findSimilar 返回 empty，强制走 L3）。
 * 解锁 L2 时：接入 PgVector，findSimilar 用向量相似检索，save 持久化簇 + embedding。
 */
public interface ClusterRepository {

    /** 按向量找最相似簇（相似度 > threshold）。 */
    Optional<ErrorCluster> findSimilar(float[] embedding, double threshold);

    /** 持久化簇。 */
    void save(ErrorCluster cluster);

    /** 按簇 ID 查询。 */
    Optional<ErrorCluster> findById(String clusterId);
}

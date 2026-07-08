package com.stackwatch.analyzer;

import com.stackwatch.domain.ErrorCluster;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClusterRepository 的 MVP 内存实现。
 * L2 向量归并未启用：findSimilar 永远返回 empty（强制走 L3）。
 * 解锁 L2 后替换为 PgVectorClusterRepository。
 */
@Component
public class InMemoryClusterRepository implements ClusterRepository {

    private final Map<String, ErrorCluster> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ErrorCluster> findSimilar(float[] embedding, double threshold) {
        // TODO(L2 解锁): 接入 PgVector 向量相似检索
        return Optional.empty();
    }

    @Override
    public void save(ErrorCluster cluster) {
        store.put(cluster.clusterId(), cluster);
    }

    @Override
    public Optional<ErrorCluster> findById(String clusterId) {
        return Optional.ofNullable(store.get(clusterId));
    }
}

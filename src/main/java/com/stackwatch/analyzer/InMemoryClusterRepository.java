package com.stackwatch.analyzer;

import com.stackwatch.domain.ErrorCluster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClusterRepository 的 MVP 内存实现。
 * L2 关闭（stackwatch.l2.enabled=false 或缺失）时生效；L2 启用时让位给 PgVectorClusterRepository。
 */
@Component
@ConditionalOnProperty(name = "stackwatch.l2.enabled", havingValue = "false", matchIfMissing = true)
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

    @Override
    public List<ErrorCluster> findAll() {
        return List.copyOf(store.values());
    }
}

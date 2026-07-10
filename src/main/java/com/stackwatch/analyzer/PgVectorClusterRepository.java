package com.stackwatch.analyzer;

import com.stackwatch.domain.ErrorCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 向量归并仓储：基于 Spring AI VectorStore 的 PgVector 实现。
 * 对应 B1 设计：③分析层 L2。
 *
 * <p>启用条件：{@code stackwatch.l2.enabled=true}（默认 false）。
 * 启用前需完成：
 * <ol>
 *   <li>pom.xml 取消注释 {@code spring-ai-starter-vector-store-pgvector}；</li>
 *   <li>application.yml 移除 {@code PgVectorStoreAutoConfiguration} 的 exclude；</li>
 *   <li>配置 {@code spring.datasource} 指向已安装 pgvector 扩展的 PostgreSQL；</li>
 *   <li>配置 LLM API Key（EmbeddingModel 由 spring-ai-starter-model-openai 提供）。</li>
 * </ol>
 *
 * <h3>Spring AI 1.0.0 API 说明（源码核实）</h3>
 * <p>{@code SearchRequest.Builder.query()} 仅接受 {@code String}，<b>不接受</b>
 * {@code float[]}（任务假设的 {@code query(float[])} 重载在 1.0.0 GA 不存在）。
 * 而 {@link ClusterRepository#findSimilar(float[], double)} 的接口契约传入的是
 * 预计算 embedding（由 {@code EmbeddingService} 产出），无法逆向还原为文本。
 *
 * <p>因此 {@code findSimilar} 当前用<b>内存余弦相似度</b>比对已存簇的
 * {@link ErrorCluster#embedding()}，仍是 0 token（不调 LLM、不重新 embed）。
 * 待 Spring AI 支持向量入参后，切换为：
 * <pre>{@code
 * // 预期 API（待 Spring AI 支持 float[] query）：
 * // List<Document> hits = vectorStore.similaritySearch(
 * //     SearchRequest.builder()
 * //         .query(embedding)            // float[] 重载，当前不可用
 * //         .topK(TOP_K)
 * //         .similarityThreshold(threshold)
 * //         .build());
 * }</pre>
 *
 * <p>{@code save} 同时持久化到 VectorStore（Document + 元数据）与本地 clusterId 映射；
 * {@code findById} 走本地映射，保证簇对象（含 analysis）完整可重建。
 *
 * <h3>不可变性</h3>
 * <p>本地 {@link #clusterIndex} 仅存储引用，{@link ErrorCluster} 本身不可变；
 * 归并时由 {@code ErrorAnalyzer} 调 {@link ErrorCluster#increment} 产生新实例再 save。
 */
@Component
@ConditionalOnProperty(name = "stackwatch.l2.enabled", havingValue = "true")
public class PgVectorClusterRepository implements ClusterRepository {

    private static final Logger log = LoggerFactory.getLogger(PgVectorClusterRepository.class);

    /** 相似检索只取最相似的 1 个簇。 */
    private static final int TOP_K = 1;

    /** Document metadata key：簇 ID。 */
    private static final String META_CLUSTER_ID = "clusterId";
    private static final String META_APP_NAME = "appName";
    private static final String META_EXCEPTION_TYPE = "exceptionType";
    private static final String META_MEMBER_COUNT = "memberCount";

    /** metadata 缺省值（Document 不允许 null value，见 Assert.noNullElements）。 */
    private static final String UNKNOWN = "unknown";

    /** 余弦相似度兜底（向量非法或全零时返回，确保不误命中）。 */
    private static final double INVALID_SIMILARITY = -1.0;

    private final VectorStore vectorStore;

    /** clusterId -> ErrorCluster 本地映射，用于 findById 与内存相似检索。 */
    private final Map<String, ErrorCluster> clusterIndex = new ConcurrentHashMap<>();

    /**
     * @param vectorStore Spring AI 向量库（L2 未配 DB 时为 null，findSimilar/save 优雅降级）
     */
    @Autowired
    public PgVectorClusterRepository(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public Optional<ErrorCluster> findSimilar(float[] embedding, double threshold) {
        if (vectorStore == null) {
            log.debug("L2 skipped: VectorStore unavailable (configure pgvector to enable)");
            return Optional.empty();
        }
        if (clusterIndex.isEmpty()) {
            return Optional.empty();
        }

        // Spring AI 1.0.0 的 SearchRequest.query() 仅接受 String（源码核实），
        // 无法直接传入预计算 float[]。当前用内存余弦相似度比对簇 embedding，
        // 保持 0 token。详见类 Javadoc「Spring AI 1.0.0 API 说明」。
        ErrorCluster best = null;
        double bestScore = INVALID_SIMILARITY;
        for (ErrorCluster cluster : clusterIndex.values()) {
            double score = cosineSimilarity(embedding, cluster.embedding());
            if (score > bestScore) {
                bestScore = score;
                best = cluster;
            }
        }
        if (best != null && bestScore >= threshold) {
            log.debug("L2 vector merged (in-memory cosine): cluster={} score={} threshold={}",
                best.clusterId(), bestScore, threshold);
            return Optional.of(best);
        }
        log.debug("L2 no match: bestScore={} threshold={}", bestScore, threshold);
        return Optional.empty();
    }

    @Override
    public void save(ErrorCluster cluster) {
        clusterIndex.put(cluster.clusterId(), cluster);
        if (vectorStore == null) {
            log.debug("L2 vector store persist skipped (VectorStore null): {}", cluster.clusterId());
            return;
        }
        Document doc = Document.builder()
            .id(cluster.clusterId())
            .text(buildClusterText(cluster))
            .metadata(META_CLUSTER_ID, cluster.clusterId())
            .metadata(META_APP_NAME, cluster.appName() == null ? UNKNOWN : cluster.appName())
            .metadata(META_EXCEPTION_TYPE,
                cluster.exceptionType() == null ? UNKNOWN : cluster.exceptionType())
            .metadata(META_MEMBER_COUNT, cluster.memberCount())
            .build();
        try {
            vectorStore.add(List.of(doc));
            log.debug("L2 saved cluster {} to vector store", cluster.clusterId());
        } catch (Exception e) {
            // 持久化失败不阻断归并流程（本地索引已更新），仅告警
            log.warn("L2 vector store persist failed for {}: {}", cluster.clusterId(), e.getMessage());
        }
    }

    @Override
    public Optional<ErrorCluster> findById(String clusterId) {
        return Optional.ofNullable(clusterIndex.get(clusterId));
    }

    @Override
    public List<ErrorCluster> findAll() {
        return List.copyOf(clusterIndex.values());
    }

    /**
     * 构建写入 VectorStore 的文本代理（exceptionType + representativeHash）。
     * VectorStore 内部会调 EmbeddingModel 对此文本生成向量并落库；
     * 真实相似检索由 {@link #findSimilar} 的内存余弦比对簇内 embedding 完成，
     * 此文本仅用于持久化与未来切换 SearchRequest 时的检索基础。
     */
    private static String buildClusterText(ErrorCluster cluster) {
        String type = cluster.exceptionType() == null ? UNKNOWN : cluster.exceptionType();
        String hash = cluster.representativeHash() == null ? UNKNOWN : cluster.representativeHash();
        return type + "\n" + hash;
    }

    /**
     * 余弦相似度：a·b / (|a|·|b|)。
     * 向量为 null、长度不一致、或任一模为零时返回 {@link #INVALID_SIMILARITY}，
     * 确保不误命中（兜底优于静默返回高相似度）。
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length != a.length) {
            return INVALID_SIMILARITY;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? INVALID_SIMILARITY : dot / denom;
    }
}

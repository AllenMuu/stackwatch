package com.stackwatch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * L2 向量归并配置入口。
 * 对应 B1 设计：③分析层 L2。
 *
 * <p>启用条件：{@code stackwatch.l2.enabled=true}（默认 false）。
 * 仅当此配置激活时，{@link com.stackwatch.analyzer.PgVectorClusterRepository}
 * 才会被实例化（同名的 @ConditionalOnProperty）；同时
 * {@link com.stackwatch.analyzer.InMemoryClusterRepository} 因
 * {@code havingValue="false", matchIfMissing=true} 让位。
 *
 * <h3>L2 解锁步骤（用户操作）</h3>
 * <ol>
 *   <li><b>pom.xml</b>：取消注释 {@code spring-ai-starter-vector-store-pgvector}，
 *       使 {@code VectorStore} / {@code PgVectorStore} 类进入 classpath；</li>
 *   <li><b>application.yml</b>：从 {@code spring.autoconfigure.exclude} 移除
 *       {@code PgVectorStoreAutoConfiguration} 与 {@code DataSourceAutoConfiguration}，
 *       让 PgVectorStore 自动装配生效；</li>
 *   <li><b>application.yml</b>：取消注释 {@code spring.datasource} 配置块，
 *       指向已安装 pgvector 扩展的 PostgreSQL（{@code CREATE EXTENSION vector}）；</li>
 *   <li><b>环境变量</b>：配置 LLM API Key（EmbeddingModel 由
 *       spring-ai-starter-model-openai 提供，用于向量化）；</li>
 *   <li>将 {@code stackwatch.l2.enabled} 置为 {@code true}。</li>
 * </ol>
 *
 * <h3>为何不在此显式定义 VectorStore bean</h3>
 * <p>{@code PgVectorStoreAutoConfiguration}（来自 pgvector starter）会在
 * 依赖 + DataSource + EmbeddingModel 就绪时自动创建 {@code VectorStore} bean。
 * 本类作为 L2 特性的 feature-flag 锚点与文档入口，不重复自动装配逻辑，
 * 避免与 starter 的自动配置产生 @ConditionalOnMissingBean 冲突。
 *
 * <p>L2 关闭时：本类与 {@code PgVectorClusterRepository} 均不装配，
 * {@code spring.autoconfigure.exclude} 进一步确保不连 DB，项目零基础设施即可启动。
 */
@Configuration
@ConditionalOnProperty(name = "stackwatch.l2.enabled", havingValue = "true")
public class VectorStoreConfig {
    // 无额外 bean 定义：PgVectorStoreAutoConfiguration 负责 VectorStore 创建，
    // PgVectorClusterRepository 通过 @Autowired(required=false) 注入。
    // 本类仅作 L2 feature-flag 开关与配置文档锚点。
}

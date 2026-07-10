# StackWatch Tech Selection

> 记录每个技术选型的备选方案、对比维度、最终决策与理由
> 选型原则：**满足当前需求 + 预留演进空间**（YAGNI + 可扩展），不是越强越好，是越合适越好

---

## 1. LLM 框架：Spring AI vs LangChain4j

| 维度 | Spring AI 1.0 | LangChain4j |
|------|--------------|-------------|
| Spring 生态集成 | 原生（@ConfigurationProperties/autoconfig） | 需手动桥接 |
| 结构化输出 | `entity(Class)` 一行搞定 | 需自己解析 |
| Function Calling | `@Tool` 注解 + `.tools(bean)` | 需注册 ToolSpecification |
| 向量库支持 | 统一 VectorStore 抽象 | 也支持，但 API 分散 |
| 学习曲线 | Spring 开发者零成本 | 需学 LangChain 概念 |

**决策：Spring AI**
- 与车险 Demo / 学习计划一致，技术栈统一
- `entity(RootCauseAnalysis.class)` 结构化输出简洁
- `@Tool` 注解 Function Calling 开发体验好
- Spring Boot 原生 autoconfig，零配置

**风险**：Spring AI 处于快速迭代期，API 签名可能变（已遇到 `SearchRequest.query()` 只接受 String 的偏差，见选型 7）。Javadoc 标注关键 API 版本。

---

## 2. 向量库：PgVector vs Milvus vs ES

| 维度 | PgVector | Milvus | ES (dense_vector) |
|------|---------|--------|-------------------|
| 规模适配 | 万级 | 百万级+ | 十万级 |
| 关系 JOIN | ✅ 同库 SQL | ❌ 需二次同步 | ⚠️ 需二次同步 |
| 运维成本 | 低（复用 PG） | 高（独立集群） | 中 |
| Spring AI 集成 | starter 完善 | starter 完善 | starter 完善 |

**决策：PgVector**
- 簇规模在万级，PgVector 够用
- **关键**：周报要 JOIN 簇表和反馈表，PgVector 能在一个库里做"向量检索 + 关系 JOIN"，Milvus 要二次同步
- 复用 PostgreSQL，少一个组件运维
- 演进：规模到百万级再考虑 Milvus

---

## 3. L1 缓存：Caffeine vs Redis

| 维度 | Caffeine | Redis |
|------|---------|-------|
| 依赖 | 零（内存） | 需 Redis 实例 |
| 延迟 | 微秒级 | 毫秒级（网络） |
| 跨实例共享 | ❌ | ✅ |
| 持久化 | ❌ | ✅ |
| 容量 | 受 JVM 堆限制 | 大 |

**决策：Caffeine（MVP）-> Redis（生产）**
- MVP 阶段零依赖，毫秒级命中
- 接口已抽象（`FingerprintCache`），切换成本低
- 生产演进：换 Redis 实现跨实例共享 + 持久化（避免重启丢缓存）
- YAGNI：先满足当前，预留演进

---

## 4. 消息队列：Kafka vs Redis Stream vs 内存

| 维度 | Kafka | Redis Stream | 内存队列 |
|------|-------|-------------|---------|
| 削峰 | ✅ 强 | 中 | ❌ |
| 解耦 | ✅ | ✅ | ❌ |
| 重放 | ✅ | ✅ | ❌ |
| 运维 | 高 | 中（复用 Redis） | 零 |
| 加分项 | ✅ | 中 | ❌ |

**决策：内存（MVP）-> Kafka（生产）**
- MVP 用 Logback Appender + HTTP 同步，零依赖
- Kafka 代码就绪（`@ConditionalOnProperty` 默认关闭），生产启用才连 broker
- 选 Kafka 而非 Redis Stream：削峰能力更强 + 技术分量更重 + 重放能力

---

## 5. LLM 模型：DashScope（通义千问）vs DeepSeek vs OpenAI

| 维度 | DashScope | DeepSeek | OpenAI |
|------|-----------|---------|--------|
| 合规 | ✅ 国内合规 | ✅ | ⚠️ |
| 成本 | 低 | 极低 | 高 |
| Function Calling | ✅ | ✅ | ✅ |
| OpenAI 兼容协议 | ✅ | ✅ | 原生 |

**决策：DashScope（默认）+ 可切换**
- 用 Spring AI 的 OpenAI 兼容协议，`base-url` 指向 DashScope 兼容端点
- 切换 DeepSeek 只改 `base-url` + `model`，零代码改动
- 成本演进：未来可做"粗分用 DeepSeek（便宜）+ 精分用 Qwen-Plus"级联

---

## 6. 指标：Micrometer + Prometheus

| 维度 | Micrometer | 自建埋点 |
|------|-----------|---------|
| 标准化 | ✅ 业界标杆 | ❌ |
| 端点 | /actuator/prometheus | 需自建 |
| 生态 | Grafana 直连 | 需适配 |
| 维度标签 | ✅ | 手动 |

**决策：Micrometer + Prometheus + actuator**
- Spring Boot 原生集成，零成本
- 指标：`analysis_path_total` / `analysis_duration_seconds` / `root_cause_confidence` / `token_cost_total`
- Grafana 直连 Prometheus 出大盘

**关键发现**：`MeterRegistryCustomizer` 在 spring-boot-actuator 里（不只加 micrometer-registry-prometheus）。且 micrometer 1.14 无 `Histogram` 顶层类，置信度分布用 `DistributionSummary`（语义等价）。

---

## 7. 指纹算法

### 7.1 hash 算法：SHA-256 vs SHA-1 vs MD5

**决策：SHA-256**
- 碰撞域比 SHA-1/MD5 大（借鉴 PostHog，工业级）
- 性能差异在异常分析场景可忽略

### 7.2 指纹输入：业务帧 vs 全栈

**决策：top-5 业务帧 + 异常类型**
- **过滤框架帧**（java./spring./netty. 等）：同一 NPE 的框架内部栈帧会因依赖版本变化而抖动，业务代码帧稳定
- 指纹要"业务语义稳定"，不被框架升级冲掉
- top-5 而非全栈：避免深层栈抖动影响
- 兜底：应用帧不足 2 帧时回退到栈顶 N 帧

### 7.3 版本化 + 可解释记录（借鉴 PostHog）

- `FingerprintVersion`（V1/V2）：算法升级时，老簇保持老版本、新簇用新版本，避免历史归并关系全部断裂
- `FingerprintRecordPart`：记录指纹由哪几帧/哪部分组成，user-facing 可查"为什么这两条没归并"

### 7.4 已知 API 偏差

Spring AI 1.0 的 `SearchRequest.query()` 只接受 String，不接受 float[]。L2 的 `findSimilar` 改用内存余弦比对已存簇的 embedding（仍 0 token）。代码注释保留切换模板待 API 支持。

---

## 8. 三层归并 vs 纯 LLM

| 维度 | 三层归并 | 纯 LLM |
|------|---------|--------|
| 成本 | 1% 调 LLM | 100% 调 LLM |
| 延迟 | 缓存命中毫秒级 | 秒级 |
| 稳定性 | L1/L2 确定性 | LLM 不稳定 |
| 实现复杂度 | 高 | 低 |

**决策：三层归并**
- 成本降两个数量级（核心卖点）
- 延迟从秒级降到毫秒级（缓存命中）
- 同堆栈归并确定性（L1/L2 不调 LLM）
- 复杂度可接受：L1/L2 是传统工程手段，L3 才用 LLM

---

## 9. Function Calling vs 纯 Prompt

| 维度 | Function Calling | 纯 Prompt |
|------|-----------------|-----------|
| 防幻觉 | ✅ 查事实 | ❌ 猜 |
| 准确率 | 高 | 中 |
| 实现复杂度 | 中（需写工具） | 低 |
| 延迟 | 高（多轮工具调用） | 低 |

**决策：Function Calling**
- 防幻觉是核心诉求（生产根因不能瞎编）
- 3 个工具：`queryRecentChanges` / `querySimilarHistory` / `queryTraceContext`
- 让 LLM 基于事实判根因，而非凭堆栈猜
- 延迟可接受：L3 只对 1% 新簇调用

---

## 10. 条件启用：@ConditionalOnProperty + autoconfigure.exclude vs 依赖注释

| 维度 | @ConditionalOnProperty + exclude | 依赖注释（车险 Demo 风格） |
|------|----------------------------------|--------------------------|
| 代码可编译 | ✅ 始终 | ❌ 注释时不可编译 |
| 运行时降级 | ✅ | N/A |
| 启用方式 | 改配置 | 取消注释 + 改配置 |
| 一致性 | 高 | 低 |

**决策：@ConditionalOnProperty + autoconfigure.exclude**
- 依赖始终激活（代码可编译），用条件控制运行时
- L2/Kafka 默认关闭，零基础设施启动
- 启用只改配置（`l2.enabled=true` + 移除 exclude），不改代码
- 与车险 Demo"依赖注释"不同：stackwatch 用 autoconfigure.exclude 更工程化

---

## 11. 不可变 record vs Lombok @Data

| 维度 | Java record | Lombok @Data |
|------|------------|--------------|
| 不可变性 | ✅ 天生 | ⚠️ 需 @Value |
| 依赖 | 零 | 需 Lombok |
| 简洁 | ✅ | ✅ |
| 兼容 | Java 14+ | 需注解处理器 |

**决策：Java record**
- 全局 coding-style 要求 immutability，record 天生不可变
- 零依赖（Java 17）
- `ErrorCluster.embedding()` 通过 compact constructor + accessor 双重 defensive copy 保证数组不可变

---

## 12. 选型决策汇总表

| 组件 | 选型 | 备选 | 核心理由 |
|------|------|------|---------|
| LLM 框架 | Spring AI 1.0 | LangChain4j | Spring 原生 + entity() 结构化输出 |
| 向量库 | PgVector | Milvus/ES | 万级够用 + 向量关系同库 JOIN |
| L1 缓存 | Caffeine | Redis | MVP 零依赖，接口可演进 |
| 消息队列 | Kafka（默认关闭） | Redis Stream | 削峰 + 重放 + 技术分量 |
| LLM 模型 | DashScope | DeepSeek/OpenAI | 合规 + 成本 + 可切换 |
| 指标 | Micrometer+Prometheus | 自建 | Spring 原生 + Grafana 生态 |
| 指纹 hash | SHA-256 | SHA-1/MD5 | 碰撞域大（PostHog 同款） |
| 归并策略 | 三层级联 | 纯 LLM | 成本降两个数量级 |
| 防幻觉 | Function Calling | 纯 Prompt | 查事实而非猜 |
| 条件启用 | @ConditionalOnProperty+exclude | 依赖注释 | 代码可编译 + 运行时降级 |
| 数据结构 | Java record | Lombok | 天生不可变 + 零依赖 |

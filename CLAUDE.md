# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

StackWatch 是 AI 驱动的 Java 生产错误根因分析系统：异常堆栈指纹归并 + LLM 根因定位 + 定时周报聚合。核心价值是「成本阶梯」——绝大多数异常在 L1/L2 免费归并，仅约 1% 真正调用 LLM。

## 构建与运行

**JDK 17 是硬性要求**（Spring Boot 3.4 + Spring AI 1.0 强制）。构建前确认 `JAVA_HOME` 指向 17，否则编译失败：
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

常用命令：
```bash
mvn clean package          # 构建
mvn spring-boot:run        # 运行（需 DASHSCOPE_API_KEY 才能真正调 LLM，否则仅 L1/L2 可用）
mvn test                   # 全量测试
mvn test -Dtest=ErrorAnalyzerUnitTest          # 单个测试类
mvn test -Dtest=ErrorAnalyzerUnitTest#l1CacheHitSkipsLlmAndEmbedding  # 单个测试方法
```

**LLM 密钥走环境变量，禁止写入代码**：`DASHSCOPE_API_KEY`（必填才调 LLM）、`LLM_BASE_URL`、`LLM_CHAT_MODEL`、`KAFKA_BOOTSTRAP`、`FEISHU_WEBHOOK_URL`、`FEISHU_API_URL`。

## 测试分层

- **纯单元测试（CI 友好，无需 LLM Key）**：`ErrorAnalyzerUnitTest`、`FingerprinterTest`。`ErrorAnalyzerUnitTest` 用 Mockito 链式 mock `ChatClient`（prompt→user→tools→call→entity），覆盖 L1 命中 / L2 归并 / L3 新簇 / 置信度兜底 / LLM 异常兜底。**核心归并逻辑的测试不应依赖真实 LLM。**
- **集成测试（需 Key）**：`ErrorAnalyzerTest` 用 `@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = "sk-.+")` 守卫，未配置时自动跳过。

## 架构（需跨多文件理解的部分）

### 五层主链路 + 两层横切

```
①采集层 collector    -> 三入口：Logback Appender / HTTP /collect / Kafka（默认关）
②预处理层 preprocess -> 指纹去重（SHA-256 + 框架帧过滤）
③分析层 analyzer     -> L1 指纹缓存 / L2 向量归并 / L3 LLM 三层级联（核心）
④聚合层 aggregator   -> 实时激增检测 + 按周 Top-N 聚合
⑤投递层 notifier     -> 飞书实时告警 + 飞书周报
横切A metrics        -> Micrometer 埋点（path 耗时/置信度/token 成本）
横切B feedback       -> /feedback 回流 few-shot，下次分析注入 prompt（数据飞轮）
```

数据流：`ErrorEventCollector`（采集）→ `Fingerprinter.generate`（预处理）→ `ErrorAnalyzer.analyze`（L1→L2→L3）→ 簇落 `ClusterRepository` → `WeeklyAggregator`/`HighFrequencyDetector`（聚合）→ `FeishuClient`（投递）。

### 三层级联归并（`ErrorAnalyzer.analyze` 是架构中枢）

- **L1** `FingerprintCache`（Caffeine）：指纹 hash 精确命中 → 复用历史 `RootCauseAnalysis`，0 token。
- **L2** `ClusterRepository.findSimilar`：向量相似归并到已有簇，0 token。`embedding == null` 时跳过（L2 关闭的信号）。
- **L3** `callLlm`：新簇调 ChatClient，`.entity(RootCauseAnalysis.class)` 做结构化输出 + `.tools(analysisTools)` 注入 `@Tool` 函数防幻觉；`postProcess` 做置信度兜底（confidence < 阈值 **或** evidence 为空 → `needHumanReview=true`；LLM 异常 → 兜底 UNKNOWN 根因）。

### 零基础设施启动 = 特性开关 + 自动装配排除

项目默认**不连 DB / 不连 Kafka / 不连向量库**即可启动，靠两处协同控制：

1. `application.yml` 的 `spring.autoconfigure.exclude` 列表（`DataSourceAutoConfiguration` / `PgVectorStoreAutoConfiguration` / `KafkaAutoConfiguration`）—— 这是总开关。
2. 各仓储/通道用 `@ConditionalOnProperty` 选择实现。

**启用 L2（PgVector）或 Kafka 时必须三处同步改**（缺一即启动失败或 bean 注入失败），步骤见 `VectorStoreConfig` 与 `KafkaCollectConfig` 的 Javadoc：
- pom.xml 取消对应 starter 注释 / 确认依赖在 classpath；
- 从 `spring.autoconfigure.exclude` 移除对应 AutoConfiguration；
- 配置 `spring.datasource`（L2）或 `spring.kafka.bootstrap-servers`（Kafka）；
- 把 `stackwatch.l2.enabled` / `stackwatch.collector.kafka.enabled` 置 `true`。

`ClusterRepository` 有两个互斥实现，由 `stackwatch.l2.enabled` 选择：`InMemoryClusterRepository`（`havingValue=false, matchIfMissing=true`，`findSimilar` 恒返回 empty 强制走 L3）vs `PgVectorClusterRepository`（`havingValue=true`）。

> 注意：即使 L2 启用，`PgVectorClusterRepository.findSimilar` 当前仍用**内存余弦相似度**比对簇 embedding，而非 `VectorStore.similaritySearch`——因为 Spring AI 1.0.0 的 `SearchRequest.query()` 只接受 `String` 不接受 `float[]`（源码核实，见该类 Javadoc）。改这块前先确认 Spring AI 版本是否已支持向量入参。

### 三种采集入口

- `/analyze`（`AnalysisController`）：直接调 `ErrorAnalyzer`，调用方自组字段。
- `/collect`（`CollectController`）：走 `ErrorEventCollector`，由采集层补 `eventId`/`occurredAt`，是对外上报标准入口。
- `LogbackErrorAppender`：Logback 反射实例化（非 Spring bean），通过内部静态 `SpringContextHolder`（`ApplicationContextAware`）取 `ErrorEventCollector`；带 `ThreadLocal` 递归保护，采集异常吞掉写 stderr 避免自递归。
- Kafka（`KafkaErrorProducer`/`KafkaErrorConsumer`）：默认关，启用后异步削峰。

### 指纹算法（`Fingerprinter`）

输入 = `exceptionType` + 规范化 top-N **应用帧**（过滤 `java./javax./org.springframework./io.netty.` 等框架前缀，因框架内部栈帧随依赖版本抖动而业务帧稳定）。应用帧不足 2 帧时回退栈顶 N 帧。`buildEmbeddingText` 供 L2 embedding 用，渲染策略由 `EmbeddingRendering` 记录（借鉴 PostHog）。指纹版本化（`FingerprintVersion`）以便算法升级不断历史归并关系。

### 度量埋点（`AnalysisMetrics`）

统一 `stackwatch.` 前缀，关键 tag `path=cache_hit|vector_merged|llm_new`：`analysis_duration_seconds`（Timer，P50/P95 量化缓存对耗时贡献）、`analysis_path_total`（Counter，成本故事 95%/4%/1%）、`root_cause_confidence`（DistributionSummary）、`token_cost_total`（占位 0，待接 ChatResponse usage）。埋点由 `ErrorAnalyzer` 在 L1/L2/L3 三处 return 前调用。

### 周报调度（`WeeklyReportScheduler`）

`@Scheduled(cron = "0 0 9 * * MON")` 周一 9:00 Asia/Shanghai：`WeeklyAggregator.aggregateWeekly` 取 Top-N 簇 → ChatClient 总结 → `FeishuClient.sendWebhook`。LLM 失败回退原始数据文本，保证周报不缺失。`@EnableScheduling` 在 `StackWatchConfig` 上。`ReportController` 提供手动触发复用入口。

## 关键约定

- **不可变**：`domain` 全部 record，集合字段在 compact constructor 做 `List.copyOf`/`Map.copyOf`；`ErrorCluster.embedding`（`float[]`）做 clone 双重防御。归并时通过 `ErrorCluster.increment` 返回新实例，绝不原地改。
- **配置**：所有 `stackwatch.*` 配置用 record `@ConfigurationProperties`，compact constructor 内做范围校验并回退默认值（如 `AnalysisProperties`、`CacheProperties`）。前缀分布：`stackwatch.analysis` / `stackwatch.cache` / `stackwatch.l2` / `stackwatch.collector.kafka` / `stackwatch.feishu`。
- **Prompt 模板**：`resources/prompts/root-cause.st` + `PromptTemplateHolder` 自行做 `{var}` 替换，**不用 Spring AI template API**（版本敏感）。新增变量需同步改模板与 `ErrorAnalyzer.callLlm` 的 vars map。
- **错误处理**：采集/投递/embedding 失败均不阻断主链路，显式 `log.warn` 后降级（embedding 失败→null→跳 L2；LLM 失败→兜底根因；飞书失败→跳过推送）。
- **Spring AI API 版本敏感**：`ChatClient` 的 `.tools()` / `.entity()` / `.content()` 签名可能随版本变化，相关类 Javadoc 已注明「以官方文档为准」。改这些调用前先核对 Spring AI 1.0.0 实际签名。
- **依赖按需解锁**：pom.xml 中 `resilience4j`、`spring-boot-starter-data-redis` 等以注释形式存在，需用时取消注释（版本由 Spring Boot parent 管理，无需显式写 version）。

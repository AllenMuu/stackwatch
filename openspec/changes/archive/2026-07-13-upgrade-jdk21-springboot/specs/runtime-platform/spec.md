<!--
Delta spec for capability: runtime-platform
本 change 新增 capability,故仅用 ADDED Requirements。
格式硬规则:Requirement 句子含 SHALL/MUST;每个 Requirement 至少一个 #### Scenario:(level-4)。
-->

## ADDED Requirements

### Requirement: 运行时版本基线

StackWatch 的运行时基线 MUST 为 JDK 21 LTS + Spring Boot 4.1.0 + Spring AI 2.0.0(官方基线组合,经 PRE-GATE-1 联网核实锁定)。`pom.xml` 中的 `spring-boot-starter-parent` 版本、`java.version`、`spring-ai.version` MUST 三者协调一致。

#### Scenario: JDK 21 构建基线

- **WHEN** 在本地或 CI 构建项目
- **THEN** `pom.xml` 的 `java.version` MUST 为 `21`,jenv 锁定 JDK 21,`mvn -v` 报告的 Java 版本 MUST 为 21

#### Scenario: Spring Boot 4.1.0 主线

- **WHEN** 解析 `pom.xml` 的 parent
- **THEN** `spring-boot-starter-parent` 版本 MUST 为 `4.1.0`(GA,2026-06-10 发布)

#### Scenario: Spring AI 2.0.0 兼容性

- **WHEN** Spring Boot 升级到 4.1.0
- **THEN** `spring-ai.version` MUST 为 `2.0.0`(其官方基线即 Spring Boot 4.1.0,组合完全匹配)

### Requirement: 零基础设施默认启动

默认配置下,StackWatch MUST 能在无数据库、无 Kafka、无向量库的环境下成功启动。`application.yml` 的 `spring.autoconfigure.exclude` MUST 包含 `DataSourceAutoConfiguration`、`PgVectorStoreAutoConfiguration`、`KafkaAutoConfiguration` 在 Spring Boot 4.1.0 / Spring AI 2.0.0 下等价且有效的全限定名(原 3.4 包路径已因 4.0 autoconfigure 模块重组而失效)。

#### Scenario: 无基础设施启动成功

- **WHEN** 未配置数据库 / Kafka / 向量库,且 `stackwatch.l2.enabled=false`、`stackwatch.collector.kafka.enabled=false`
- **THEN** 应用 MUST 成功启动,`/actuator/health` MUST 返回 UP

#### Scenario: autoconfigure exclude 类名核对

- **WHEN** Spring Boot 升级到 4.1.0
- **THEN** `spring.autoconfigure.exclude` 列表中每一项 MUST 在 4.1.0 / Spring AI 2.0.0 下为有效的 autoconfiguration 类全限定名;原 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 等旧路径已失效,MUST 更新为 4.1 下等价全限定名

### Requirement: 基础设施启用三处同步

启用 L2(PgVector)或 Kafka 时,MUST 同步完成三处变更:pom 依赖加入 classpath、从 `spring.autoconfigure.exclude` 移除对应项、配置 `spring.datasource` 或 `spring.kafka` 并将 `stackwatch.l2.enabled` 或 `stackwatch.collector.kafka.enabled` 置为 `true`。缺少任一将导致启动或 bean 注入失败。

#### Scenario: 启用 L2 三处同步

- **WHEN** 启用 L2 向量归并
- **THEN** `pom.xml` MUST 含 `spring-ai-starter-vector-store-pgvector`,`spring.autoconfigure.exclude` MUST 移除 `PgVectorStoreAutoConfiguration` 与 `DataSourceAutoConfiguration` 的 4.1 等价项,`spring.datasource.*` MUST 配置,`stackwatch.l2.enabled` MUST 为 `true`

#### Scenario: 启用 Kafka 三处同步

- **WHEN** 启用采集层 Kafka 通道
- **THEN** `pom.xml` MUST 含 `spring-kafka`,`spring.autoconfigure.exclude` MUST 移除 `KafkaAutoConfiguration` 的 4.1 等价项,`spring.kafka.bootstrap-servers` MUST 配置,`stackwatch.collector.kafka.enabled` MUST 为 `true`

### Requirement: Starter 重组补全

Spring Boot 4.0 起 tomcat/jetty runtime 拆为独立 starters、micrometer metrics starter 缺失。升级后 `pom.xml` MUST 补全这些被重组的 starter,使 web 启动与 `/actuator/prometheus` 端点保持可用。

#### Scenario: Web 启动可用

- **WHEN** 升级到 Spring Boot 4.1.0 后启动应用
- **THEN** 内嵌 web 容器(tomcat)MUST 正常启动;若 4.0 要求显式 runtime starter,`pom.xml` MUST 含对应 tomcat starter

#### Scenario: Prometheus 端点可用

- **WHEN** 升级后访问 `/actuator/prometheus`
- **THEN** 端点 MUST 返回 prometheus 格式指标;若 4.0 的 micrometer metrics starter 缺失,`pom.xml` MUST 补全

### Requirement: 核心合并逻辑测试不依赖 LLM

`ErrorAnalyzerUnitTest` 与 `FingerprinterTest` MUST 使用 Mockito 链式 mock `ChatClient`,覆盖 L1 命中、L2 合并、L3 新建集群、置信度兜底、LLM 异常兜底,且 MUST NOT 依赖真实 LLM key。运行时基线升级后,这些测试 MUST 全部通过。

#### Scenario: 升级后单测全绿

- **WHEN** 运行时基线升级到 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 并运行 `mvn test -Dtest=ErrorAnalyzerUnitTest,FingerprinterTest`
- **THEN** 所有测试 MUST 通过,且执行期间 MUST NOT 要求 `DASHSCOPE_API_KEY`

#### Scenario: LLM 异常兜底

- **WHEN** L3 调用 `ChatClient` 抛出异常
- **THEN** `ErrorAnalyzer` MUST 返回 UNKNOWN 根因兜底结果(`needHumanReview=true`),MUST NOT 将异常向上抛出导致主流程中断

### Requirement: 升级范围限定为版本跃迁与兼容修复

本次升级 MUST 仅包含使代码在 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 下编译通过且测试通过的最小必要改动(含 starter 重组补全)。MUST NOT 主动用 JDK 21 新语法(record patterns、pattern matching for switch)重构现有代码,MUST NOT 解除 `PgVectorClusterRepository` 的内存余弦绕过,MUST NOT 启用 L2 或 Kafka,MUST NOT 引入新功能。

#### Scenario: 不引入新语法重构

- **WHEN** 升级完成后审查 diff
- **THEN** diff MUST NOT 包含以使用 record patterns / pattern matching for switch 为目的的现有代码重构(仅允许为修复编译错误的必要改动)

#### Scenario: 不解除向量绕过

- **WHEN** 升级完成后审查 `PgVectorClusterRepository`
- **THEN** `findSimilar` MUST 仍使用内存余弦相似度,MUST NOT 改为 `VectorStore.similaritySearch`(即使 Spring AI 2.0 支持 `float[]` 输入)

### Requirement: 降级路径

PRE-GATE-1 已核实 Spring Boot 4.1.0 + Spring AI 2.0.0 可行,默认无需降级。但若 apply 阶段发现 4.1.0 + Spring AI 2.0.0 存在不可解 breaking,MUST 降级到 Spring Boot 3.5.x + Spring AI 1.1.x;若 3.5 仍不可行,MUST 降级到 3.4 最新 patch + Spring AI 1.0.0。降级决策 MUST 在 design 与 tasks 中记录理由并 MUST 告知用户。降级是计划内分支,MUST NOT 视为失败。

#### Scenario: 4.1.0 不可行降级 3.5

- **WHEN** apply 阶段发现 Spring Boot 4.1.0 + Spring AI 2.0.0 存在不可解 breaking
- **THEN** 运行时基线 MUST 降级到 Spring Boot 3.5.x + Spring AI 1.1.x,且 design 的 Decisions 与 tasks MUST 记录降级理由

#### Scenario: 4.1.0 与 3.5 均不可行降级 3.4 patch

- **WHEN** Spring Boot 4.1.0 与 3.5 均判定不可行
- **THEN** 运行时基线 MUST 降级到 Spring Boot 3.4 最新 patch + Spring AI 1.0.0,且 MUST 记录降级理由并告知用户

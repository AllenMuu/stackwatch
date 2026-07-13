## Why

StackWatch 当前运行时基线为 JDK 17 + Spring Boot 3.4.0 + Spring AI 1.0.0。JDK 17 已逐步进入维护尾期,团队希望跟进 JDK 21 LTS 的长期支持与运行时改进(虚拟线程、分代 ZGC、record patterns)。联网核实(PRE-GATE-1,2026-07-10)确认 Spring Boot 4.1.0 已 GA,且 Spring AI 2.0.0 的官方基线正是 Spring Boot 4.1.0(无对应 4.0.x 的 Spring AI)。本次将运行时基线整体跃迁到 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0,同步获得 Spring Framework 7 / Jakarta EE 11 的长期支持窗口,为后续依赖 JDK 21 特性的能力铺路。

## What Changes

**运行时版本基线**
- From: JDK 17 + Spring Boot 3.4.0 + Spring AI 1.0.0(BOM 管控)
- To: JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0(官方基线组合,完全匹配)
- Reason: 跟进 LTS 与主线长期支持,避免技术债;Spring AI 2.0.0 官方基线即 4.1.0
- Impact: breaking(编译期 + 运行时行为变化,需全量回归)

**零基础设施启动 autoconfigure.exclude**
- From: exclude 三项 3.4 全限定名(`org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` / `org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration` / `org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration`)
- To: 重新核对并更新为 4.1.0 / Spring AI 2.0.0 下的新全限定名(已联网确认原包路径 404,autoconfigure 模块结构已重组)
- Reason: Spring Boot 4.0 重组 autoconfigure 包结构,原类名失效会导致启动尝试连接 DB/Kafka/向量库而失败
- Impact: breaking(若未同步则零基础设施启动失败)

**Spring AI API 调用**
- From: `ErrorAnalyzer.callLlm` 基于 Spring AI 1.0.0 的 `ChatClient` `.tools()` / `.entity()` / `.content()` 签名
- To: 适配 Spring AI 2.0.0 的等价签名(查 2.0 upgrade notes,跨大版本可能变更)
- Reason: Spring AI 1.0 -> 2.0 跨大版本,API 敏感(CLAUDE.md 明确警告)
- Impact: breaking(仅当签名变更时需改 `callLlm`,编译期即可发现)

**Starter / 自动装配副作用**
- From: `spring-boot-starter-web` 隐含 tomcat runtime;`spring-boot-starter-actuator` + `micrometer-registry-prometheus` 自动装配 metrics
- To: 4.0 起 tomcat/jetty runtime 拆为独立 starters;micrometer metrics starter 缺失需补(`Starter for spring-boot-micrometer-metrics is missing`)
- Reason: Spring Boot 4.0 release notes 明确的 starter 重组
- Impact: breaking(可能影响 web 启动与 prometheus 端点,需冒烟验证)

**JDK 工具链**
- From: jenv 锁定 JDK 17
- To: jenv 锁定 JDK 21
- Reason: 匹配新基线
- Impact: non-breaking(本地构建环境配置)

## Capabilities

### New Capabilities

- `runtime-platform`: 项目运行时版本基线(JDK / Spring Boot / Spring AI 版本组合)与零基础设施启动机制(`autoconfigure.exclude` 主开关 + `@ConditionalOnProperty` 双机制),包含启用 L2 / Kafka 时的三处同步约束。

### Modified Capabilities

<!-- openspec/specs/ 当前为空,无现有 capability 的 spec-level 行为变更。本次升级不改产品行为,仅改运行时基线契约。 -->

## Impact

- **pom.xml**: `spring-boot-starter-parent` 3.4.0 -> 4.1.0、`java.version` 17 -> 21、`spring-ai.version` 1.0.0 -> 2.0.0;可能需补 tomcat runtime starter 与 micrometer metrics starter
- **application.yml**: `spring.autoconfigure.exclude` 三项类全限定名重新核对(已确认原路径 404,需查新路径);`spring.ai.openai.*` 配置键在 Spring AI 2.0 下是否保持
- **ErrorAnalyzer.callLlm**: Spring AI 2.0 `ChatClient` API 签名适配(条件性,编译期发现)
- **PgVectorClusterRepository**: Spring AI 2.0 `SearchRequest` API(本次不解除内存余弦绕过,仅核对仍可编译)
- **LogbackErrorAppender**: `ThreadLocal` 递归守卫在虚拟线程下的行为验证(若 4.1 默认启用虚拟线程)
- **VectorStoreConfig / KafkaCollectConfig Javadoc**: 三处同步约束的类名引用若变动需同步注释
- **构建环境**: jenv 切换到 21;CI(若有)JDK 镜像升级
- **测试**: `ErrorAnalyzerUnitTest` / `FingerprinterTest`(纯单测,无 LLM)必须升级后全绿;`ErrorAnalyzerTest`(集成,需 key)可选验证

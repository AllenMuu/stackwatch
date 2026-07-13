<!--
Raw capture of superpowers:brainstorming output.

本檔原樣捕捉 brainstorming skill 的產出，不強制結構。
Skill 的自然產出通常是 decision log 格式（背景 -> 決議鏈 Q1-Qn -> 設計取捨），
但依對話內容可能有不同組織方式。

design.md 從本檔萃取並重新整理為結構化設計文件。

不要將本檔的內容複製到 design.md - design.md 是獨立的重組產物，
兩者互補但不重疊。
-->

# Brainstorm: 升级 JDK 21 + Spring Boot 4.1.0

## 背景

StackWatch 当前基线:
- JDK 17(强制,jenv 管理)
- Spring Boot 3.4.0(parent)
- Spring AI 1.0.0(BOM 管控)
- 零基础设施启动(autoconfigure.exclude + @ConditionalOnProperty 双机制)
- 五层管道 + 两横切层;`ErrorAnalyzer` 是架构枢纽(L1/L2/L3 级联)
- domain 全 record + 不可变约定;`ErrorCluster.embedding` 防御性 clone

用户诉求:升级 JDK 到 21,Spring Boot 升到"适配 21 的稳定版"。

约束(来自项目 CLAUDE.md,影响升级决策的关键项):
- Spring AI API 版本敏感:`.tools()` / `.entity()` / `.content()` 签名跨版本可能变
- Spring AI 1.0.0 的 `SearchRequest.query()` 只接受 `String`(已在 `PgVectorClusterRepository` 用内存余弦绕过)
- 启用 L2 / Kafka 需三处同步(pom + autoconfigure.exclude + 配置 + flag)
- LLM 密钥仅走环境变量,不可硬编码
- 测试分层:`ErrorAnalyzerUnitTest` 用 Mockito 链式 mock `ChatClient`,核心合并逻辑测试不得依赖真实 LLM

## 决议链

### Q1: Spring Boot 主线方向?

- 方案 A:3.4 最新 patch + Spring AI 1.0.0 不变(风险最小,纯收获 JDK 21)
- 方案 B:3.5.x(更新主线,需核实 Spring AI 兼容)
- 方案 C:最新主线 4.x + 对应 Spring AI(最新,但 breaking changes 风险最高)

**决策:方案 C - 升级到 Spring Boot 4.1.0 + Spring AI 2.0.0。** 用户选择最新主线;联网核实(PRE-GATE-1,2026-07-10)确认 Spring AI 2.0.0 官方基线即 Spring Boot 4.1.0(无对应 4.0.x 的 Spring AI),故锁定 4.1.0(2026-06-10 GA)+ Spring AI 2.0.0(2026-06-12 GA),接受 breaking changes 风险与全量回归成本。

### Q2(由 Q1 派生): Spring Boot 4.x 在 2026.7 是否已 GA?对应哪个 Spring AI 版本?

**已联网核实(PRE-GATE-1 完成,2026-07-10):**
- Spring Boot 4.0.7 与 4.1.0 均已 GA(2026-06-10);parent pom `<java.version>17</java.version>`,JDK 基线 17,支持 JDK 21(向上兼容)
- Spring AI 版本对应关系已查明:
  - Spring AI 1.0.x / 1.1.x -> Spring Boot 3.5.15
  - Spring AI 2.0.0(2026-06-12 GA)-> Spring Boot 4.1.0
- **无对应 Spring Boot 4.0.x 的 Spring AI 版本**

**决策:锁定 Spring Boot 4.1.0 + Spring AI 2.0.0(官方基线组合,完全匹配)。** 用户确认采用推荐方案。降级路径(3.5 + Spring AI 1.1 / 3.4 patch + Spring AI 1.0)保留为计划内分支,但当前无需触发。

### Q3: JDK 21 是否引入新语言特性到代码库?

- JDK 21 LTS 特性:Record patterns、Pattern matching for switch(已 GA)、Virtual threads、Sequenced collections、String templates(preview)
- 项目 domain 全是 record,理论上可用 record patterns 简化 `Fingerprinter` / `ErrorAnalyzer` 里的 instanceof 链
- 虚拟线程:Spring Boot 4.1 可能默认启用,对 Kafka consumer / 阻塞式 LLM 调用线程模型友好

**决策:本次升级以"版本跃迁 + 兼容性"为范围,不主动重构现有代码用新语法(YAGNI)。** 仅做让代码在 JDK 21 + Spring Boot 4.1.0 下编译通过 + 测试通过的必要改动。新语法红利(record patterns / 虚拟线程调优)留后续独立 change。但 design 须标注:虚拟线程默认行为若变化,对该项目阻塞式 LLM 调用 + Caffeine + ThreadLocal 递归守卫(`LogbackErrorAppender`)的影响需在 apply 阶段验证。

### Q4: breaking changes 的回退与验证策略?

4.1 breaking changes 可能落在:
- Jakarta EE 跃迁(更多 `javax.*` -> `jakarta.*`;3.4 已是 jakarta,预计增量小)
- autoconfigure 类名/位置变动(直接影响零基础设施启动的 `spring.autoconfigure.exclude` 列表;已核实原包路径 404)
- starter 重组或拆分(已核实 tomcat/jetty runtime 改为 starters、micrometer metrics starter 缺失)
- 配置绑定语义 / `@ConfigurationProperties` 行为
- Spring AI API 签名(`.tools()` / `.entity()` / `.content()`)
- Hibernate 7 / JPA 元数据(影响 PgVector 路径,虽默认关闭)

**决策:分阶段验证,每阶段设回退点。** pom 版本切换 -> 编译通过 -> 纯单元测试(无 LLM,`ErrorAnalyzerUnitTest` / `FingerprinterTest`)-> 集成测试(需 key,可选)-> 启动冒烟(零基础设施模式)。任一阶段失败,记录 breaking 点,评估代码适配 vs 版本回退。

### Q5: 升级是否波及零基础设施启动机制?

- 零基础设施启动 = `application.yml` 的 `spring.autoconfigure.exclude` 主开关 + 每个 repo/channel 的 `@ConditionalOnProperty`
- 4.1 已确认重组 autoconfigure 模块结构(原包路径 404),exclude 列表若不更新会失效 -> 启动尝试连 DB/Kafka/向量库 -> 启动失败
- `VectorStoreConfig` / `KafkaCollectConfig` 的 Javadoc 明确警告"三处同步"

**决策:升级后必须重新核对 `spring.autoconfigure.exclude` 里的每个类全限定名在 4.1.0 下的新路径。** 这是升级最高危点之一,单独列为 task。

## 设计取舍

- **范围克制**:只升版本 + 修兼容,不顺带重构(YAGNI)。新语法红利独立 change。
- **前置核实已完成**:PRE-GATE-1 联网核实锁定 4.1.0 + Spring AI 2.0.0,无需降级。
- **降级路径保留**:若 apply 阶段发现 4.1.0 + Spring AI 2.0.0 有不可解 breaking,降级 3.5 + Spring AI 1.1,再不行 3.4 patch,每级有触发条件。
- **测试分层保护**:`ErrorAnalyzerUnitTest`(Mockito,无 LLM)是核心安全网,升级后必须先全绿,才推进集成测试。
- **不确定项显式标注**:autoconfigure 新全限定名、Spring AI 2.0 API 签名差异,标注为 apply 阶段核实项,不在 planning 阶段编造。

## 已核实(2026-07-10 联网)

- Spring Boot 4.1.0 GA(2026-06-10),JDK 基线 17(parent pom 确认),支持 JDK 21
- Spring AI 2.0.0 GA(2026-06-12),基线 Spring Boot 4.1.0,组合完全匹配
- `autoconfigure.exclude` 三项类在 4.0.7 原包路径(`spring-boot-autoconfigure/.../jdbc|kafka/`)均 404,模块结构已重组,Task 5 须查新全限定名
- Spring Boot 4.0 release notes 关键 breaking:tomcat/jetty runtime 改为 starters;"Auto-configuration exclusions are checked using a different class loader";micrometer metrics starter 缺失需补

## 仍待 apply 阶段核实

- `autoconfigure.exclude` 三项类(`DataSourceAutoConfiguration` / `PgVectorStoreAutoConfiguration` / `KafkaAutoConfiguration`)在 4.1.0 / Spring AI 2.0.0 下的新全限定名(原路径已 404)
- 4.1.0 是否默认启用虚拟线程,及对 `LogbackErrorAppender` ThreadLocal 递归守卫的影响
- Jakarta EE 11 是否引入项目尚未迁移的 `javax.*`(预计无,grep 确认)
- Spring AI 2.0.0 下 `ChatClient` `.tools()` / `.entity()` / `.content()` 与 1.0.0 的签名差异(查 2.0 upgrade notes)
- Spring AI 2.0.0 下 `SearchRequest.query()` 是否支持 `float[]`(若支持可解除 `PgVectorClusterRepository` 绕过,本次不做,仅记录)

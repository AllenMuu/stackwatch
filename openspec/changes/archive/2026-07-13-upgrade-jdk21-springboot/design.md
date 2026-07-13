## Context

StackWatch 当前运行时基线:JDK 17(jenv 管理)+ Spring Boot 3.4.0(parent)+ Spring AI 1.0.0(BOM)。架构为五层管道(Collector -> Preprocess -> Analyzer -> Aggregator -> Notifier)+ 两横切层(metrics / feedback),`ErrorAnalyzer.analyze` 是架构枢纽,串联 L1 指纹缓存 / L2 向量归并 / L3 LLM 三层级联。

零基础设施启动是核心运维特征:默认无 DB / Kafka / 向量库即可启动,由 `application.yml` 的 `spring.autoconfigure.exclude` 主开关 + 每个 repo/channel 的 `@ConditionalOnProperty` 双机制实现。启用 L2 / Kafka 需三处同步(pom + exclude 移除 + 配置 + flag)。

关键约束(CLAUDE.md):
- Spring AI API 版本敏感:`.tools()` / `.entity()` / `.content()` 签名跨版本可能变
- Spring AI 1.0.0 的 `SearchRequest.query()` 只接受 `String`(`PgVectorClusterRepository` 已用内存余弦绕过)
- domain 全 record + 不可变约定
- 测试分层:`ErrorAnalyzerUnitTest` 用 Mockito 链式 mock `ChatClient`,核心合并逻辑测试不依赖真实 LLM

**已联网核实(PRE-GATE-1,2026-07-10):** Spring Boot 4.1.0 GA(2026-06-10),JDK 基线 17,支持 JDK 21;Spring AI 2.0.0 GA(2026-06-12),官方基线即 Spring Boot 4.1.0。组合完全匹配,无需降级。另已确认:Spring Boot 4.0 重组了 autoconfigure 模块结构(三项 exclude 类原包路径 404),tomcat/jetty runtime 拆为独立 starters,micrometer metrics starter 缺失。

## Goals / Non-Goals

**Goals:**
- 运行时基线跃迁到 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0(官方基线组合)
- 保持零基础设施启动能力(默认无 DB / Kafka / 向量库可启动)
- 保持五层管道 + 两横切层架构与外部行为不变(版本跃迁,非功能变更)
- 核心合并逻辑测试(`ErrorAnalyzerUnitTest` / `FingerprinterTest`)升级后全绿
- 降级路径保留:apply 阶段若发现 4.1.0 + Spring AI 2.0.0 有不可解 breaking,降级 3.5 + Spring AI 1.1,再不行 3.4 patch + Spring AI 1.0

**Non-Goals:**
- 不主动用 JDK 21 新语法(record patterns / pattern matching for switch)重构现有代码
- 不解除 `PgVectorClusterRepository` 的内存余弦绕过(即使 Spring AI 2.0 `SearchRequest` 支持 `float[]`)
- 不启用 L2 / Kafka(保持默认关闭)
- 不引入新功能或新依赖(除 4.0 starter 重组所需的补全)
- 不调整 L1 / L2 / L3 级联逻辑与指纹算法
- 不做性能调优(虚拟线程调优留后续 change)

## Decisions

### D1:升级目标 = Spring Boot 4.1.0 + JDK 21 + Spring AI 2.0.0
- **选择**:跃迁到 4.1.0 主线,JDK 升至 21,Spring AI 升至 2.0.0
- **理由**:联网核实确认 Spring AI 2.0.0 官方基线即 Spring Boot 4.1.0(无对应 4.0.x 的 Spring AI);4.1.0 已 GA(2026-06-10),JDK 基线 17 支持 21;组合完全匹配,获取 Spring Framework 7 / Jakarta EE 11 长期支持窗口
- **已考虑 alternative**:
  - Spring Boot 4.0.7 + Spring AI 2.0.0(非官方组合,Spring AI 2.0 基线是 4.1,有版本不匹配风险,被否)
  - 3.4 最新 patch + Spring AI 1.0.0(风险最小但停在末班车,作为降级备选)
  - 3.5.x + Spring AI 1.1(折中,作为降级备选)

### D2:前置核实 gate(PRE-GATE-1)已完成
- **选择**:apply 前已联网核实 4.1.0 GA + Spring AI 2.0.0 兼容,锁定版本组合
- **理由**:版本组合是整个 change 的硬依赖,核实后无不确定性
- **结果**:4.1.0 + Spring AI 2.0.0 确认可行,无需降级;降级路径保留为计划内分支

### D3:范围 = 仅版本跃迁 + 兼容修复,不重构
- **选择**:YAGNI,本次只做让代码在 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 下编译通过 + 测试通过的必要改动,不顺带用新语法重构
- **理由**:控制本次 change 风险面与回归范围;新语法红利(record patterns / 虚拟线程调优)独立 change 处理
- **已考虑 alternative**:顺带用 record patterns 重构 `Fingerprinter` / `ErrorAnalyzer` 的 instanceof 链(增加回归风险,违背单一变更原则,被否)

### D4:验证策略 = 分阶段,测试分层保护
- **选择**:pom 版本切换 -> 编译通过 -> 纯单测全绿(`ErrorAnalyzerUnitTest` / `FingerprinterTest`)-> autoconfigure 核对 + 启动冒烟(零基础设施)-> 集成测试(可选,需 key)
- **理由**:`ErrorAnalyzerUnitTest` 用 Mockito 链式 mock `ChatClient`,不依赖真实 LLM,是核心合并逻辑的安全网,必须先全绿才推进后续
- **已考虑 alternative**:直接跑全量集成测试(需 key,且 breaking 定位困难,被否)

### D5:autoconfigure.exclude 高危点单独处理(已确认重组)
- **选择**:升级后逐一核对 `spring.autoconfigure.exclude` 三项类在 4.1.0 / Spring AI 2.0.0 下的新全限定名;联网已确认原包路径(`spring-boot-autoconfigure/.../jdbc|kafka/`)404,模块结构已重组
- **理由**:exclude 失效会导致启动尝试连接 DB / Kafka / 向量库,零基础设施启动失败,且错误信息可能误导
- **已考虑 alternative**:盲信类名不变(高风险,被否)

### D6:Spring AI API 适配以编译期为准(1.0 -> 2.0 跨大版本)
- **选择**:`ErrorAnalyzer.callLlm` 的 `.tools()` / `.entity()` / `.content()` 调用,以升级后编译期错误为信号,按 Spring AI 2.0 官方 upgrade notes 做最小适配
- **理由**:Spring AI 1.0 -> 2.0 跨大版本,API 可能变更,但具体签名需见到 2.0 才能确定
- **已考虑 alternative**:预先猜测 API 变更并改写(可能改错方向,被否)

### D7:Starter 重组副作用补全(4.0 release notes 已确认)
- **选择**:核对并补全 4.0 starter 重组带来的缺失--tomcat/jetty runtime 拆为独立 starters(影响 `spring-boot-starter-web` 启动)、micrometer metrics starter 缺失(影响 `/actuator/prometheus`)
- **理由**:Spring Boot 4.0 release notes 明确"Change tomcat and jetty runtime modules to starters"与"Starter for spring-boot-micrometer-metrics is missing",不补全会导致 web 启动失败或 metrics 端点消失
- **已考虑 alternative**:仅改版本号不补 starter(冒烟阶段才暴露,被否)

## Risks / Trade-offs

- [Risk] `spring.autoconfigure.exclude` 类全限定名在 4.1.0 变动(已确认原路径 404)-> Mitigation: D5 逐一核对新路径,启动冒烟验证零基础设施模式
- [Risk] Spring AI 1.0 -> 2.0 API 签名变更(`.tools` / `.entity` / `.content`)-> Mitigation: 编译期发现,按 2.0 upgrade notes 最小适配
- [Risk] 4.0 starter 重组导致 web/metrics 启动异常 -> Mitigation: D7 补全 tomcat runtime starter 与 micrometer metrics starter,冒烟验证 `/actuator/prometheus`
- [Risk] 4.1 默认启用虚拟线程,影响 `LogbackErrorAppender` 的 `ThreadLocal` 递归守卫 -> Mitigation: 启动冒烟 + 核对 4.1 默认值;虚拟线程下 `ThreadLocal` 仍可用但语义需确认
- [Risk] Jakarta EE 11 引入新的 `javax.*` -> `jakarta.*` 迁移 -> Mitigation: grep `javax\.` 确认(预计无,因 3.4 已是 jakarta)
- [Risk] Hibernate 7 / JPA 元数据变动影响 PgVector 路径 -> Mitigation: PgVector 默认关闭,本次不启用,低风险
- [Trade-off] 选择 4.1.0 而非 4.0.7,以匹配 Spring AI 2.0 官方基线 -> 接受理由:4.0.7 无对应 Spring AI,4.1.0 是官方匹配组合
- [Trade-off] 不顺带用 JDK 21 新语法重构 -> 接受理由:单一变更原则,降低回归面
- [Trade-off] 不解除 `PgVectorClusterRepository` 内存余弦绕过 -> 接受理由:控制范围,且 L2 默认关闭,解除留待启用 L2 的 change

## Migration Plan

- **阶段 0(PRE-GATE-1,已完成)**:联网核实锁定 Spring Boot 4.1.0 + Spring AI 2.0.0,组合匹配,无需降级。
- **阶段 1**:jenv 切换到 21;pom 版本切换(parent 4.1.0 / java.version 21 / spring-ai.version 2.0.0)。
- **阶段 2**:编译通过,修复 breaking(预计 Spring AI 2.0 API + autoconfigure 类名 + starter 补全)。
- **阶段 3**:纯单测全绿(`ErrorAnalyzerUnitTest` / `FingerprinterTest`)。
- **阶段 4**:`autoconfigure.exclude` 三项核对 + 零基础设施启动冒烟(无 key、无 DB、无 Kafka 启动成功)+ `/actuator/prometheus` 验证。
- **阶段 5**:集成测试(可选,需 `DASHSCOPE_API_KEY`)。
- **回滚**:`git revert` 本次提交;pom 版本回退;jenv 回 17。单一 commit 原则,便于回滚。

## Open Questions

- `autoconfigure.exclude` 三项类在 4.1.0 / Spring AI 2.0.0 下的新全限定名(原路径已 404,apply 阶段查官方迁移文档 + 实跑)
- 4.1.0 是否默认启用虚拟线程,以及对 `LogbackErrorAppender` ThreadLocal 递归守卫的影响
- Spring AI 2.0 下 `ChatClient` `.tools()` / `.entity()` / `.content()` 与 1.0 的具体签名差异(查 2.0 upgrade notes)
- 4.0 starter 重组后,`spring-boot-starter-web` 是否需显式加 tomcat runtime starter;micrometer metrics starter 的精确 artifactId
- Spring AI 2.0 `SearchRequest.query()` 是否支持 `float[]`(影响是否解除 `PgVectorClusterRepository` 绕过;本次不做,仅记录)

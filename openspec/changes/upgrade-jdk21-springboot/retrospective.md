# Retrospective: upgrade-jdk21-springboot

> Written: 2026-07-10 (after verify passed)
> Commit range: `07e8b86..952c0b6` (6 implementation commits; verify/retrospective added after)
> Worktree: .claude/worktrees/upgrade-jdk21-springboot

## 0. Evidence

- **Commit range**: `07e8b86..952c0b6` (6 commits)
- **Diff size**: +86 / -73 lines across 12 files
- **Tasks done**: 38/38
- **Subagent dispatches**: 13 (6 implementer + 5 task reviewer + 1 final reviewer [opus] + 2 fix; Task 1/2/7 done by controller/planning)
- **Locked versions**: JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0
- **Tests**: 11/11 green (1 integration skipped, no LLM key)
- **Startup**: zero-infra smoke green

## 1. Wins

- **PRE-GATE-1 前置核实避免盲升**:联网核实发现 Spring AI 2.0.0 基线是 Spring Boot 4.1.0(非 4.0),无对应 4.0.x 的 Spring AI。用户原选 4.0,核实后调整为 4.1.0+2.0.0 官方组合,避免版本不匹配。
- **信号驱动修复而非臆测**:plan 假设 Spring AI 1.0->2.0 ChatClient API breaking,implementer 以 `mvn compile` 实际错误为信号,发现真实 breaking 是 Spring Boot 4.1 包路径重组(MeterRegistryCustomizer + RestTemplateBuilder),ChatClient API 未变(`tools(Object...)` 仍支持 @Tool POJO)。避免改错方向。
- **FQN 证据充分**:autoconfigure.exclude 三项新全限定名,DataSource/PgVector 用本地 jar 证实,Kafka 用 GitHub 源 + 4.1 源码确认 exclude absent class 是静默 no-op(`AutoConfigurationImportSelector.checkExcludedClasses` 用 `ClassUtils.isPresent`)。未臆测包路径。
- **手术刀式 diff**:12 文件 +86/-73,纯版本跃迁 + 兼容修复,无 scope 蔓延(无新语法重构、PgVector 绕过保留、L2/Kafka 关闭)。
- **测试分层保护**:`ErrorAnalyzerUnitTest`(Mockito,无 LLM)是安全网,升级后 11/11 绿。
- **reviewer 发现真实问题**:Task 8 reviewer 发现配置键 `chat.options.*` -> `chat.*` 扁平化(implementer 原以为启动成功=键有效);final reviewer 发现 KafkaCollectConfig 4.1 starter guidance 缺失 + Javadoc 版本漂移。

## 2. Misses

- **plan 对 breaking 源假设错误**:plan 假设 Spring AI API breaking,实际是 Spring Boot 包重组。信号驱动纠正,但 Task 4 commit message 预设(`fix analyzer`)与实际(`fix config`)不符,implementer 合理偏离。
- **虚拟线程验证覆盖不足**:Task 6 无法触发 LogbackErrorAppender 路径(非法 JSON 被框架 400 拦截),ThreadLocal 递归守卫在虚拟线程下未直接验证。未观察到错误,但覆盖缺口。
- **Task 8 配置键改动未当场启动验证**:implementer 改 `chat.*` 后只跑 `mvn test`(mock,不验证绑定),未重新启动。reviewer flag,Task 9 才补启动验证。
- **多文件 Javadoc 版本漂移**:final review 发现 PgVector/MetricsConfig/AnalysisMetrics/KafkaCollectConfig Javadoc 仍引用旧版本,final fix 集中同步。应在各 task 改动时顺带更新。
- **EnterWorktree baseRef=head 配置未生效**:worktree 基于 origin/main(缺失 planning commits),需 fast-forward merge main 修复。

## 3. Lessons / Actions

- **PRE-GATE-1 模式可复用**:依赖升级前联网核实版本兼容矩阵(GitHub releases + Maven Central + release notes),避免盲升。沉淀为升级类 change 标准前置。
- **信号驱动修复优于臆测**:plan 不预设 breaking 细节,让 implementer 以编译错误为信号。plan 的 commit message 应允许 implementer 按实际调整(或标注"可按实际调整")。
- **配置改动当场启动验证**:改 `@ConfigurationProperties` 键后,立即 `mvn spring-boot:run` 确认绑定无告警,不依赖后续 task。
- **Javadoc 版本同步纳入 task scope**:改某文件时,顺带 grep 同文件版本引用并更新,避免 final review 集中 fix。
- **worktree baseRef**:EnterWorktree 前 `git config worktree.baseRef head`,或 commit planning 后创建,避免基于 origin/main 丢失本地 commits。

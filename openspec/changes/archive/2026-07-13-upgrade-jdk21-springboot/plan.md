# Upgrade JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 StackWatch 运行时基线从 JDK 17 + Spring Boot 3.4.0 + Spring AI 1.0.0 跃迁到 JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0(官方基线组合),保持零基础设施启动能力与核心合并逻辑测试全绿。

**Architecture:** 纯依赖升级 + 兼容修复,不改五层管道 / 两横切层架构。改动集中在 `pom.xml`(版本 + starter 补全)、`application.yml`(`autoconfigure.exclude` 新全限定名)、可能的 `ErrorAnalyzer.callLlm`(Spring AI 1.0 -> 2.0 API 适配)。`autoconfigure.exclude` 三项类原包路径已确认 404(4.0 模块重组),是最高危点。

**Tech Stack:** JDK 21 LTS, Spring Boot 4.1.0, Spring AI 2.0.0, Maven, jenv, Caffeine, Micrometer, spring-ai-starter-model-openai, spring-ai-starter-vector-store-pgvector, spring-kafka

## Global Constraints

(摘自 `specs/runtime-platform/spec.md`,每个 task 隐含遵守)

- JDK 基线 MUST 为 21 LTS;`pom.xml` 的 `java.version` = `21`,jenv 锁定 21
- Spring Boot MUST 为 `4.1.0`(GA,2026-06-10,PRE-GATE-1 已核实)
- Spring AI MUST 为 `2.0.0`(GA,2026-06-12,官方基线即 Spring Boot 4.1.0)
- 默认配置 MUST 能在无 DB / Kafka / 向量库下启动;`spring.autoconfigure.exclude` 三项 MUST 为 4.1.0 / Spring AI 2.0.0 下的有效全限定名(原路径已 404)
- `ErrorAnalyzerUnitTest` / `FingerprinterTest` MUST 用 Mockito mock `ChatClient`,不依赖 `DASHSCOPE_API_KEY`,升级后全绿
- 仅做版本跃迁 + 兼容修复 + 4.0 starter 重组补全;MUST NOT 用 JDK 21 新语法重构;MUST NOT 解除 `PgVectorClusterRepository` 内存余弦绕过;MUST NOT 启用 L2 / Kafka
- 降级路径(apply 阶段若发现不可解 breaking):4.1.0 -> 3.5 + Spring AI 1.1 -> 3.4 patch + Spring AI 1.0

---

### Task 1: PRE-GATE-1 版本核实(已完成)

**状态:** 已于 2026-07-10 联网核实完成,结果如下,apply 阶段直接采用,跳过核实步骤。

**核实结果:**
- Spring Boot 4.1.0 GA(2026-06-10),JDK 基线 17(parent pom 确认),支持 JDK 21
- Spring AI 2.0.0 GA(2026-06-12),官方基线 Spring Boot 4.1.0(release notes "Upgrade to Spring Boot 4.1.0")
- 无对应 Spring Boot 4.0.x 的 Spring AI 版本(1.x 基线 3.5.15,2.0 基线 4.1.0)
- 锁定:`SB_VERSION=4.1.0`,`SAI_VERSION=2.0.0`(用户确认推荐方案)
- `autoconfigure.exclude` 三项类原包路径在 4.0.7 已 404(模块重组),Task 5 须查新全限定名
- 4.0 release notes breaking:tomcat/jetty runtime 改为 starters、micrometer metrics starter 缺失、autoconfigure exclusions 类加载器变更

- [x] **Step 1: 核实完成,无需 apply 阶段重复**

> 无 commit(规划阶段调查)。

---

### Task 2: JDK 工具链切换到 21

**Files:**
- 无文件改动(本地环境配置)

**Interfaces:**
- Produces: `mvn -v` 报告 JDK 21,供后续 Task 的构建使用

- [ ] **Step 1: 安装/确认 JDK 21**

Run: `jenv versions`
Expected: 列表中存在 `21`(或 `21.x`)。若不存在,用 SDKMAN/Manual 安装后 `jenv add /path/to/jdk-21`。

- [ ] **Step 2: 项目级锁定 JDK 21**

Run: `jenv local 21`
Expected: 在项目根生成/更新 `.java-version` 文件,内容为 `21`。

- [ ] **Step 3: 验证 Maven 使用 JDK 21**

Run: `mvn -v`
Expected: 输出包含 `Java version: 21`。

> 无 commit(环境配置)。

---

### Task 3: pom 版本基线切换 + starter 补全

**Files:**
- Modify: `pom.xml:10`(`<version>3.4.0</version>` -> `4.1.0`)
- Modify: `pom.xml:21`(`<java.version>17</java.version>` -> `21`)
- Modify: `pom.xml:22`(`<spring-ai.version>1.0.0</spring-ai.version>` -> `2.0.0`)
- Modify: `pom.xml`(补全 4.0 starter 重组所需的 tomcat runtime starter / micrometer metrics starter)

**Interfaces:**
- Consumes: Task 1 锁定的 `SB_VERSION=4.1.0` / `SAI_VERSION=2.0.0`
- Produces: pom 基线切换完成,`mvn dependency:tree` 无冲突(供 Task 4 编译)

- [ ] **Step 1: 修改 java.version**

`pom.xml:21`:
```xml
<java.version>21</java.version>
```

- [ ] **Step 2: 修改 Spring Boot parent 版本**

`pom.xml:10`:
```xml
<version>4.1.0</version>
```

- [ ] **Step 3: 修改 Spring AI 版本**

`pom.xml:22`:
```xml
<spring-ai.version>2.0.0</spring-ai.version>
```

- [ ] **Step 4: 补全 4.0 starter 重组**

查 Spring Boot 4.0 迁移文档,确认:
- `spring-boot-starter-web` 是否需显式加 tomcat runtime starter(4.0 起 tomcat/jetty runtime 拆为独立 starters);若需要,补 `spring-boot-starter-tomcat`(或对应 runtime starter)
- micrometer metrics starter 缺失(`Starter for spring-boot-micrometer-metrics is missing`),补全对应 starter 以保持 `/actuator/prometheus` 可用

- [ ] **Step 5: 验证依赖解析**

Run: `mvn dependency:tree -Dverbose`
Expected: 无冲突;`spring-boot-starter-web` / `spring-ai-starter-model-openai` / `spring-ai-starter-vector-store-pgvector` / `spring-kafka` 均解析到 4.1.0 / 2.0.0 一致版本。

- [ ] **Step 6: Commit**

```bash
git add pom.xml
git commit -m "chore(build): bump jdk 21, spring boot 4.1 and spring ai 2.0"
```

> 此 commit 后预期 `mvn compile` 可能失败(breaking),由 Task 4 修复。计划内 RED 状态。

---

### Task 4: 编译期 breaking 修复(Spring AI 1.0 -> 2.0)

**Files:**
- Modify: `src/main/java/com/stackwatch/.../ErrorAnalyzer.java`(`callLlm` 方法,仅当 Spring AI 2.0 API 签名变更时)

**Interfaces:**
- Consumes: Task 3 的 pom 基线
- Produces: `mvn clean compile` 通过(供 Task 5/6/7)

> 信号驱动:不预判 breaking,以 `mvn compile` 实际错误为修复依据。

- [ ] **Step 1: 触发编译并收集错误**

Run: `mvn clean compile`
Expected: 收集编译错误。重点关注 `ErrorAnalyzer.callLlm` 的 `ChatClient` `.tools()` / `.entity()` / `.content()`(Spring AI 1.0 -> 2.0 跨大版本)。

- [ ] **Step 2: 适配 Spring AI 2.0 API(条件性)**

仅当 Step 1 报 `ChatClient` 编译错误时执行。查 Spring AI 2.0 upgrade notes(https://docs.spring.io/spring-ai/reference/upgrade-notes.html)对 `ErrorAnalyzer.callLlm` 做最小适配,保留 `postProcess` 置信度兜底与 LLM 异常兜底逻辑不变(spec 要求)。

- [ ] **Step 3: 确认无 javax.* 残留**

Run: `grep -rn "javax\." src/main/java/ || echo "no javax imports"`
Expected: `no javax imports`。

- [ ] **Step 4: 验证编译通过**

Run: `mvn clean compile`
Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(analyzer): adapt spring ai 2.0 chatclient api"
```
> 若 Step 2 无需改动,本任务无 commit,合入 Task 5。

---

### Task 5: autoconfigure.exclude 核对与更新(高危,已确认重组)

**Files:**
- Modify: `src/main/resources/application.yml`(`spring.autoconfigure.exclude` 列表)
- Modify: `src/main/java/com/stackwatch/.../VectorStoreConfig.java`(Javadoc 类名引用)
- Modify: `src/main/java/com/stackwatch/.../KafkaCollectConfig.java`(Javadoc 类名引用)

**Interfaces:**
- Consumes: Task 3 的 Spring Boot 4.1.0 基线
- Produces: exclude 三项为 4.1.0 有效全限定名;零基础设施启动前置就绪(供 Task 6)

> 最高危点:三项类原包路径已确认 404(4.0 重组 autoconfigure 模块)。

- [ ] **Step 1: 查 DataSourceAutoConfiguration 在 4.1.0 的新全限定名**

原 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 已 404。查 Spring Boot 4.1.0 迁移文档/源码,确认新全限定名(autoconfigure 模块拆分后的新位置)。

- [ ] **Step 2: 查 PgVectorStoreAutoConfiguration 在 Spring AI 2.0 的新全限定名**

原 `org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration`。查 Spring AI 2.0 源码确认新全限定名(可能移到 `auto-configurations/` 模块)。

- [ ] **Step 3: 查 KafkaAutoConfiguration 在 4.1.0 的新全限定名**

原 `org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration`。查 4.1.0 确认新全限定名。

- [ ] **Step 4: 更新 application.yml 的 exclude 列表**

`src/main/resources/application.yml` 的 `spring.autoconfigure.exclude`:
```yaml
    exclude:
      - <DataSourceAutoConfiguration 在 4.1.0 的新全限定名>
      - <PgVectorStoreAutoConfiguration 在 Spring AI 2.0 的新全限定名>
      - <KafkaAutoConfiguration 在 4.1.0 的新全限定名>
```

- [ ] **Step 5: 同步 VectorStoreConfig / KafkaCollectConfig Javadoc**

检查两文件 Javadoc 是否硬编码引用上述类全限定名;若有变动,同步更新注释,保持"三处同步"警告准确。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/stackwatch/**/VectorStoreConfig.java src/main/java/com/stackwatch/**/KafkaCollectConfig.java
git commit -m "chore(config): align autoconfigure.exclude with spring boot 4.1 classpaths"
```

---

### Task 6: 零基础设施启动冒烟

**Files:**
- 无文件改动(验证型)

**Interfaces:**
- Consumes: Task 3-5 的改动
- Produces: 零基础设施启动 + prometheus 端点验证通过

- [ ] **Step 1: 无 key / 无 DB / 无 Kafka 启动**

Run: `mvn spring-boot:run`
Expected: 启动完成,日志无 autoconfigure 类找不到错误,出现 `Started StackWatchApplication`。

- [ ] **Step 2: 健康检查**

Run: `curl -s localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 3: Prometheus 端点验证(starter 补全确认)**

Run: `curl -s localhost:8080/actuator/prometheus | head -5`
Expected: 返回 prometheus 格式指标(`# HELP ...` / `# TYPE ...`)。若 404 或空,说明 micrometer metrics starter 未补全,回 Task 3 Step 4。

- [ ] **Step 4: 虚拟线程影响验证(条件性)**

若 Spring Boot 4.1 默认启用虚拟线程,触发一次日志路径验证 `LogbackErrorAppender` 的 `ThreadLocal` 递归守卫(调用 `/collect` 传非法 payload 触发 appender)。Expected: 无 `StackOverflowError`;appender 正常吞掉收集异常。若虚拟线程导致 `ThreadLocal` 语义问题,记录到 Open Questions。

- [ ] **Step 5: 停止应用**

Run: `Ctrl+C` 或 `kill <pid>`

> 无 commit(验证型)。

---

### Task 7: 核心测试验证

**Files:**
- 无文件改动(验证型)

**Interfaces:**
- Consumes: Task 3-5 的改动
- Produces: 核心合并逻辑测试全绿

- [ ] **Step 1: 纯单测(无 LLM key)**

Run: `mvn test -Dtest=ErrorAnalyzerUnitTest,FingerprinterTest`
Expected: `Tests run: N, Failures: 0, Errors: 0`,不要求 `DASHSCOPE_API_KEY`。
- 若失败:区分"实现 breaking"(回 Task 4 修)vs"测试 mock 过时"(仅在新 API 不匹配时调整 mock,保持覆盖意图)。

- [ ] **Step 2: 全量测试(集成测试自动 skip)**

Run: `mvn test`
Expected: `ErrorAnalyzerTest` 自动 skip;其余全绿。

- [ ] **Step 3: 集成测试(可选,需 key)**

若环境有 `DASHSCOPE_API_KEY`:
Run: `DASHSCOPE_API_KEY=sk-... mvn test -Dtest=ErrorAnalyzerTest`
Expected: 集成测试通过。无 key 则跳过。

> 无 commit(验证型)。

---

### Task 8: 配置与文档同步

**Files:**
- Modify: `src/main/resources/application.yml`(`spring.ai.openai.*` 配置键,仅当 Spring AI 2.0 变更键名时)
- Modify: `CLAUDE.md`("JDK 17 is mandatory" -> "JDK 21 is mandatory";Spring Boot 3.4 -> 4.1、Spring AI 1.0 -> 2.0)
- Modify: `README.md`(若有版本要求说明)

**Interfaces:**
- Consumes: Task 1 锁定的版本组合

- [ ] **Step 1: 核对 spring.ai.openai 配置键**

检查 Spring AI 2.0 下 `spring.ai.openai.api-key` / `base-url` / `chat.options.model` / `temperature` 键名是否保持。若变更,更新 `application.yml`。

- [ ] **Step 2: 更新 CLAUDE.md**

将 "JDK 17 is mandatory (Spring Boot 3.4 + Spring AI 1.0 require it)" 改为:
> JDK 21 is mandatory (Spring Boot 4.1 + Spring AI 2.0 require it).
同步 jenv 版本号描述(17 -> 21)。

- [ ] **Step 3: 核对 README**

Run: `grep -nE "JDK|Java|Maven|Spring Boot|spring-ai" README.md`
Expected: 同步版本表述。无则跳过。

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md src/main/resources/application.yml
git commit -m "docs: sync jdk 21, spring boot 4.1 and spring ai 2.0 requirements"
```

---

### Task 9: 全量验收与提交

**Files:**
- 无文件改动(验收型)

**Interfaces:**
- Consumes: Task 1-8 全部完成
- Produces: 单一可发布 commit / PR

- [ ] **Step 1: 全量构建**

Run: `mvn clean package`
Expected: `BUILD SUCCESS`,测试全绿(集成测试无 key 时 skip)。

- [ ] **Step 2: diff 范围审查(spec 限定)**

Run: `git diff main --stat`
Expected: 改动文件限于 `pom.xml` / `application.yml` / `ErrorAnalyzer.java`(条件)/ `VectorStoreConfig.java` / `KafkaCollectConfig.java` / `CLAUDE.md` / `README.md`。
Run: `git diff main -- src/main/java/`
Expected: 不含以使用 record patterns / pattern matching for switch 为目的的现有代码重构。

- [ ] **Step 3: 确认未解除 PgVector 绕过**

Run: `grep -n "similaritySearch\|cosine" src/main/java/com/stackwatch/**/PgVectorClusterRepository.java`
Expected: `findSimilar` 仍用内存余弦(`cosine`),无 `similaritySearch`。

- [ ] **Step 4: PR 描述准备**

PR 描述包含:
- 版本变更前后对比(JDK 17->21, Spring Boot 3.4.0->4.1.0, Spring AI 1.0.0->2.0.0)
- PRE-GATE-1 核实结果(4.1.0 + SAI 2.0.0 官方基线组合)
- `autoconfigure.exclude` 三项新全限定名核对结果
- starter 补全情况(tomcat runtime / micrometer metrics)
- 测试结果(`ErrorAnalyzerUnitTest` / `FingerprinterTest` 全绿;集成测试 skip/通过)
- 虚拟线程影响验证结果(若适用)

- [ ] **Step 5: 推送与 PR**

```bash
git push -u origin <branch>
gh pr create --title "chore(build): upgrade to jdk 21, spring boot 4.1 and spring ai 2.0" --body <pr-description>
```

---

## Self-Review

**1. Spec coverage**(`specs/runtime-platform/spec.md` 逐 requirement 核对):

- 运行时版本基线 -> Task 1(已核实)+ Task 3(pom 切换 4.1.0/2.0.0)✓
- 零基础设施默认启动 -> Task 5(exclude 核对)+ Task 6(启动冒烟)✓
- 基础设施启用三处同步 -> Task 5(Javadoc 同步)+ spec 已记录约束 ✓
- Starter 重组补全 -> Task 3 Step 4(补全)+ Task 6 Step 3(prometheus 验证)✓
- 核心合并逻辑测试不依赖 LLM -> Task 7 Step 1 ✓;LLM 异常兜底 -> 现有 `ErrorAnalyzerUnitTest` 覆盖,Task 7 验证 ✓
- 升级范围限定(不引入新语法/不解除绕过) -> Task 9 Step 2/3 审查 ✓
- 降级路径 -> Task 1 已核实无需降级;Global Constraints 保留降级分支 ✓

**2. Placeholder scan:** 版本号已全部锁定为具体值(4.1.0 / 2.0.0 / 21),无 `SB_VERSION`/`SAI_VERSION` 占位。Task 4 的 Spring AI 2.0 API 适配为信号驱动(以 `mvn compile` 错误为依据),给出命令 + 官方 upgrade notes 链接 + 分支处理,未臆造不确定的 API 代码。Task 5 的 autoconfigure 新全限定名为"apply 阶段查源码确认"(原路径已 404,无法在 planning 预知新路径),这是诚实的调查步骤,非 placeholder。无 "TBD"/"implement later" 等空泛步骤。

**3. Type consistency:** `SB_VERSION=4.1.0` / `SAI_VERSION=2.0.0` 在 Task 1 定义,Task 3 引用一致。`autoconfigure.exclude` 三项在 Task 5 核对、Task 6 验证,一致。`ErrorAnalyzer.callLlm` 在 Task 4 适配,Task 7 测试验证,命名一致。

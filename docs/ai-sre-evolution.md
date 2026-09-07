# StackWatch AI SRE Evolution

> StackWatch 从 JVM 生产错误根因分析工具，演进为面向应用层事故调查的 AI SRE / Incident Intelligence 平台。
>
> 本文记录当前架构结论与后续演进方向，重点吸收 `alparn/sre-agent`、`fuzzylabs/sre-agent`、`HolmesGPT` 的设计思想，同时保留 StackWatch 自身的 Application/JVM-first 差异化。

---

## 1. 结论摘要

StackWatch 不应该演进成 HolmesGPT 的 Java 复刻版，也不应该把所有异常都交给一个通用 Agent 自主调查。

更适合 StackWatch 的目标定位是：

> **AI-native incident investigation and root-cause analysis for JVM applications.**
>
> 面向 Java/JVM 生产应用的 AI 事故调查与根因分析平台。

StackWatch 当前已经具备一条非常有价值的低成本分析链路：

```text
Error Event
   ↓
L1 Fingerprint Cache
   ↓ miss
L2 Semantic Cache / Cluster
   ↓ miss
L3 LLM RCA
```

这条链路不应该被 Agent 替换，而应该成为未来 AI SRE 架构的 **Fast Path**。

真正需要新增的是第二条路径：

```text
New / Severe / Uncertain Incident
              ↓
     Deep Investigation Agent
              ↓
 Metrics / Logs / Trace / Code / K8s
              ↓
           Evidence
              ↓
             RCA
              ↓
      Recommendation / Verify
```

最终形成：

```text
StackWatch
=
Incident Intelligence Engine
+
SRE Investigation Agent
```

---

## 2. StackWatch 当前已有能力

当前 StackWatch 已经具备 AI SRE 的前半段能力：

- Logback / HTTP / Kafka 错误采集入口
- SHA-256 + 框架帧过滤 + 版本化的异常指纹
- L1 精确指纹缓存
- L2 语义向量归并 / Semantic Cache
- L3 LLM 根因分析
- Spring AI Function Calling
- 结构化 RCA 输出
- `ContextOptimizer` 对 Prompt 和 Tool Output 做上下文裁剪
- 三档复核门控：
  - `AUTO_CONFIRMED`
  - `NEEDS_CONFIRMATION`
  - `NEEDS_HUMAN_REVIEW`
- 置信度 + Evidence 双重判断
- Few-shot 正样本 + Anti-pattern 负样本反馈飞轮
- Micrometer / Prometheus 可观测性
- 高频错误激增检测
- 飞书告警与周报

这些能力意味着 StackWatch 已经不是一个简单的“把异常堆栈扔给 LLM”的 Demo。

它现在已经拥有两个未来非常重要的基础：

1. **Signal Reduction**：先聚类、去重、缓存，再决定是否调用模型。
2. **Evidence-aware RCA**：RCA 不是只依赖自然语言生成，还要求证据和人工反馈。

因此后续演进应继续围绕这两个优势展开，而不是推翻现有分析层。

---

## 3. 为什么不应该复制 HolmesGPT

HolmesGPT 的优势是生产级、通用、基础设施覆盖广：

- Kubernetes
- Prometheus
- Loki
- Cloud
- Database
- CI/CD
- MCP
- Remediation
- Skills
- Approval
- Security

但 StackWatch 的优势不在于“工具数量更多”，而在于：

- 从 JVM Exception 原生进入故障调查
- Stack Trace Fingerprint
- Java Frame / Exception Type 语义
- 错误聚类
- Semantic RCA Cache
- 大量重复错误的低成本处理
- Code / Trace / Metrics / Logs 之间的应用上下文关联

因此两者定位应该不同：

| HolmesGPT | StackWatch |
|---|---|
| Infrastructure / Kubernetes first | Application / JVM first |
| Agent first | Signal reduction first |
| 通用 Incident Investigation | Java Error-driven Investigation |
| 大量通用 Toolsets | JVM / Java Production Context |
| 每次事故启动调查 | L1/L2 快路径 + Agent 深路径 |
| Infrastructure remediation | Application RCA first |

StackWatch 不应该竞争“谁支持的数据源更多”，而应该把 Java/JVM 应用错误调查做得更深。

---

## 4. 双速 AI SRE 架构

未来 StackWatch 最核心的设计建议是 **Fast Path + Deep Path**。

```text
                        Error Event
                            │
                            ▼
                    Fingerprint Engine
                            │
                  ┌─────────┴─────────┐
                  │                   │
             Known Incident      New / Abnormal
                  │                   │
                  ▼                   ▼
             FAST PATH           DEEP PATH
                  │                   │
            L1 Fingerprint       Incident Agent
                  │                   │
            L2 Semantic          Skill Selection
                  │                   │
            Historical RCA       Tool Investigation
                  │                   │
                  │        Metrics / Logs / Trace
                  │        GitHub / Kubernetes
                  │                   │
                  │                Evidence
                  │                   │
                  │                  RCA
                  │                   │
                  └─────────┬─────────┘
                            │
                        Incident
                            │
                  ┌─────────┼──────────┐
                  ▼         ▼          ▼
                Alert     Memory    Remediation
                                         │
                                      Approval
                                         │
                                      Execute
                                         │
                                       Verify
```

### 4.1 Fast Path

Fast Path 继续使用 StackWatch 当前最有价值的 L1/L2/L3 架构。

目标：

> 绝大多数重复生产错误，不启动复杂 Agent Loop。

路径：

```text
L1 Fingerprint
      ↓ miss
L2 Semantic Cache
      ↓ miss
L3 Lightweight RCA
```

适用于：

- 已知错误
- 已知异常簇
- 历史 RCA 置信度足够
- 没有明显激增
- 风险级别较低

### 4.2 Deep Path

只有真正值得调查的问题才升级为 Incident：

建议触发条件：

```text
New Cluster
OR
Error Surge
OR
High Severity
OR
RCA Confidence < threshold
OR
No Evidence
OR
Human requested deep investigation
```

Deep Path 不再只分析单个 Stack Trace，而是主动收集生产证据。

---

## 5. 三个参考项目分别借鉴什么

### 5.1 alparn/sre-agent：Agent Runtime

StackWatch 主要借鉴：

- 显式 Agent Loop
- `IncidentContext`
- `AgentStep`
- `Decision`
- Tool Registry
- Approval Gate
- Memory Write
- Context Compression

核心思想：

```text
State
+
LLM
+
Tools
+
Loop
+
Guardrails
+
Memory
```

StackWatch 不应该让 Spring AI、LangGraph 或其他 Framework 的内部状态成为自己的核心 Domain Model。

建议保持自己的 Incident State Machine。

建议 Agent Loop：

```text
OBSERVE
   ↓
REASON
   ↓
HYPOTHESIS
   ↓
ACT / TOOL CALL
   ↓
EVIDENCE
   ↓
VERIFY
   ↓
LEARN
```

---

### 5.2 fuzzylabs/sre-agent：Evaluation

StackWatch 主要借鉴：

- PydanticAI / Structured Output 的思想
- MCP Tool Integration
- Tool Call Evaluation
- Diagnosis Quality Evaluation
- 可重复的 Incident Dataset

StackWatch 已经有人工反馈，因此非常适合继续演化成 Eval Dataset：

```text
Engineer Feedback
       │
       ├── correct
       └── wrongRootCause
              │
              ▼
          Eval Dataset
              │
     ┌────────┼─────────┐
     ▼        ▼         ▼
Fingerprint  RCA       Tool
   Eval      Eval      Eval
```

未来建议至少评估：

- Fingerprint Precision / Recall / F1
- RCA Accuracy
- Evidence Grounding
- Tool Selection Accuracy
- Tool Ordering Accuracy
- Remediation Accuracy
- False Action Rate
- End-to-End Incident Resolution Rate

---

### 5.3 HolmesGPT：Toolsets / Skills / Security

StackWatch 主要借鉴：

- Toolset Architecture
- Tool Registry
- MCP Integration
- `SKILL.md`
- Remediation Tool 分级
- Human Approval
- Context Window Management
- Tool Output Spill / Truncate
- Prompt Injection 防护
- SSRF / Network / Credential 安全

HolmesGPT 对 StackWatch 最大的价值不是它支持多少数据源，而是它已经开始解决生产 Agent 真正会遇到的问题：

- 不可信日志中的 Prompt Injection
- Tool 权限边界
- Approval bypass
- MCP Authentication
- 过大的 Tool Result 撑爆 Context Window
- 私网扫描 / SSRF
- Remediation 的 Blast Radius

这些问题应在 StackWatch 进入 Remediation 前提前纳入设计。

---

## 6. 目标架构

建议未来形成以下结构：

```text
                    Alert / Error Sources
                            │
                            ▼
                    Incident Manager
                            │
             ┌──────────────┴──────────────┐
             │                             │
             ▼                             ▼
       Fast Analyzer                Incident Agent
       L1 / L2 / L3                       │
             │                    ┌────────┼─────────┐
             │                    │        │         │
             │                  Skills   Memory    Tools
             │                                      │
             │                         ┌────────────┼───────────┐
             │                         │            │           │
             │                      Metrics        Logs       Trace
             │                         │            │           │
             │                       GitHub      Kubernetes  Deployment
             │                                      │
             └──────────────────────┬───────────────┘
                                    │
                                 Evidence
                                    │
                                    ▼
                                   RCA
                                    │
                                 Policy
                                    │
                              Human Approval
                                    │
                                Remediation
                                    │
                                 Verify
                                    │
                                  Learn
                                    │
                                 Memory
```

最终公式：

```text
StackWatch
=
现有 Fingerprint + Semantic Cache + LLM RCA + Feedback
+
alparn 的 Agent Loop / Approval Model
+
fuzzylabs 的 Agent Evaluation
+
HolmesGPT 的 Toolsets / Skills / Security / Context Management
```

---

## 7. 建议新增 Domain Model

不要直接用 Framework 对象作为领域模型。

建议新增：

```text
Incident
IncidentTrigger
IncidentContext
AgentStep
Observation
Evidence
Hypothesis
InvestigationPlan
ToolCall
ToolResult
ApprovalRequest
RemediationAction
VerificationResult
IncidentReport
IncidentMemory
```

### Incident

一次真正需要持续调查的生产问题。

不是每一个 `ErrorEvent` 都创建 Incident。

### Evidence

所有 RCA 都应绑定证据，例如：

```text
Evidence
├── type
│   ├── STACKTRACE
│   ├── LOG
│   ├── METRIC
│   ├── TRACE
│   ├── CODE
│   ├── DEPLOYMENT
│   └── KUBERNETES
├── source
├── summary
├── rawRef
├── collectedAt
└── confidence
```

### AgentStep

Agent 的行为必须可审计。

建议记录：

```text
OBSERVATION
HYPOTHESIS
TOOL_CALL
TOOL_RESULT
PLAN
APPROVAL_REQUEST
APPROVAL_RESULT
ACTION
VERIFICATION
MEMORY_WRITE
```

不要保存模型私有 Chain-of-Thought，只记录可审计的 Decision Summary 和 Evidence。

---

## 8. Toolset 演进

第一阶段不要接几十种系统。

StackWatch 推荐优先支持：

### Logs Toolset

- Loki
- Elasticsearch
- Application Log API

### Trace Toolset

优先考虑 JVM 常用链路平台：

- SkyWalking
- Tempo
- ARMS 等可扩展 Adapter

### Metrics Toolset

- Prometheus
- JVM Metrics
- HTTP Metrics
- DB / Redis Metrics

### GitHub Toolset

根据 Stack Trace Frame：

```text
OrderService.java:42
```

定位：

- source code
- blame
- recent commit
- related diff

### Kubernetes Toolset

第一阶段只做 Read Only：

- Pod
- Deployment
- Events
- Restart count
- OOMKilled
- Image version

### Deployment Toolset

这是 StackWatch 很值得强化的一层：

```text
service
version
commit SHA
deploy time
release diff
```

从而支持：

```text
Error Surge
   ↓
Deploy 17 minutes ago
   ↓
GitHub Diff
   ↓
RCA
```

---

## 9. Skills：把 SRE SOP Agent 化

未来建议增加：

```text
skills/
├── java/
│   └── null-pointer/
│       └── SKILL.md
├── jvm/
│   └── oom/
│       └── SKILL.md
├── redis/
│   └── connection-pool-exhaustion/
│       └── SKILL.md
├── mysql/
│   └── slow-query/
│       └── SKILL.md
├── spring/
│   └── feign-timeout/
│       └── SKILL.md
└── thread-pool/
    └── saturation/
        └── SKILL.md
```

Skill 的含义不是 Prompt Template，而是：

> **Procedural Knowledge：遇到某类事故应该怎么调查。**

推荐结构：

```yaml
---
name: redis-connection-pool-exhaustion
description: Diagnose Redis connection pool exhaustion
severity:
  - warning
  - critical
tools:
  - metrics
  - logs
  - redis
---
```

正文至少包含：

```text
Goal
Investigation Steps
Expected Evidence
Hypotheses
Remediation
Verification
Escalation
```

---

## 10. Skills 与 Memory 必须分离

### Skill

回答：

> 这种问题应该怎么处理？

属于：

```text
Procedural Knowledge
```

### Memory

回答：

> 以前发生过类似问题吗？当时怎么解决？

属于：

```text
Experience Knowledge
```

StackWatch 当前的 L2 Semantic Cache 也不能直接等同于长期 Memory。

未来建议分成四层：

```text
Fingerprint Cache
       ↓
Semantic RCA Cache
       ↓
Historical Incident Memory
       ↓
Skill-guided Agent Investigation
```

建议 `IncidentMemory` 至少包含：

```text
fingerprint
service
symptoms
version
evidence[]
hypotheses[]
rootCause
confidence
remediation
verification
engineerFeedback
occurredAt
```

第一阶段不需要立即引入 Graph Memory。

可先使用：

```text
PostgreSQL
+
Markdown Incident Report
+
Vector Search
```

---

## 11. Context / Evidence Architecture

未来接入真实 Logs / Trace 后，不能把全部 Tool Result 原样塞入 LLM Context。

建议：

```text
Tool Result
     │
     ├── Small
     │     ↓
     │ Inline Context
     │
     └── Large
           ↓
      Evidence Store
           ↓
      Summary + Ref
           ↓
       Agent Context
```

例如：

```text
Loki query returned 12,481 errors.
Dominant error: RedisConnectionException.
93% originate from order-api.

Evidence: evidence://incident-123/loki-04
```

因此必须坚持：

> **Tool Output != Agent Context**

ContextOptimizer 后续应从简单截断升级为：

```text
filter
truncate
summarize
spill
reference
```

---

## 12. Evaluation Architecture

Evaluation 不应该在 Agent 做完以后补，而应和 Agent Runtime 同期建设。

建议建立 `evals/`：

```text
evals/
├── fingerprint/
├── diagnosis/
├── tool-call/
├── evidence/
├── remediation/
└── incidents/
```

每个模拟 Incident 应定义：

```text
expected root cause
required tools
forbidden tools/actions
expected evidence
expected remediation
expected verification
```

评测至少分四层：

### Level 1 - Tool

- Tool selection
- Tool args
- Tool order
- Unnecessary tool calls

### Level 2 - RCA

- Root cause accuracy
- Evidence grounding
- Affected service accuracy

### Level 3 - Remediation

- Correct action
- Safe action
- Minimal blast radius
- Rollback availability

### Level 4 - End-to-End

```text
Alert
 ↓
Investigation
 ↓
RCA
 ↓
Plan
 ↓
Approval
 ↓
Fix
 ↓
Verify
```

---

## 13. V4 演进路线：Incident Intelligence

建议在现有 V1-V3 基础上新增 V4。

### V4.0 - Incident Domain

实现：

```text
Incident
Evidence
AgentStep
IncidentContext
```

目标：从“单次 Error Analyze”升级为“持续 Incident Investigation”。

### V4.1 - Read-only Toolsets

优先：

```text
Prometheus
Logs
Trace
GitHub
Kubernetes Read Only
Deployment Metadata
```

目标：形成 Evidence-grounded RCA。

### V4.2 - Skills

实现：

```text
SKILL.md
Skill Registry
Skill Matching
Skill Loader
```

先实现 3~5 个高价值 JVM / Java Skill。

### V4.3 - Deep Investigation Agent

实现显式 Agent Loop：

```text
OBSERVE
 ↓
REASON
 ↓
HYPOTHESIS
 ↓
TOOL
 ↓
EVIDENCE
 ↓
RCA
```

Agent 只处理 Fast Path 无法高置信度解决的问题。

### V4.4 - Incident Memory

持久化：

```text
Root Cause
Evidence
Remediation
Verification
Engineer Feedback
```

并支持类似 Incident 检索。

### V4.5 - Agent Evaluation

建立：

- RCA Dataset
- Tool Call Dataset
- Incident E2E Dataset
- Regression Eval

---

## 14. V5 演进路线：Guarded Remediation

**V5 之前不要把自动修复作为项目主线。**

V4 最重要的目标是：

```text
StackTrace
     ↓
StackTrace + Logs + Trace + Metrics + Code
     ↓
Evidence-grounded RCA
```

只有当调查和评测体系稳定后，才进入 V5。

### V5.0 - Policy Engine

Tool Risk Model：

```text
READ_ONLY
SAFE_WRITE
REVERSIBLE
DESTRUCTIVE
```

### V5.1 - Approval

```text
Tool
 ↓
Risk Classification
 ↓
Policy
 ↓
Human Approval
 ↓
Executor
```

### V5.2 - Limited Remediation

第一批只允许低风险、可回滚操作：

```text
restart deployment
scale deployment
rollback deployment
```

第一阶段禁止：

```text
arbitrary shell
arbitrary kubectl
SQL DELETE
DROP TABLE
PVC deletion
node drain
```

### V5.3 - Verification

修复完成不等于 Incident Resolved。

必须：

```text
Execute
 ↓
Wait
 ↓
Metrics
 ↓
Logs
 ↓
Health
 ↓
Verify
```

---

## 15. 核心安全原则

### Principle 1 - LLM 没有生产权限

```text
LLM = Untrusted Decision Maker
```

LLM 可以：

- reason
- propose
- select
- recommend

最终权限由以下确定性系统决定：

```text
Tool metadata
Policy
RBAC
Allowlist
Network Policy
Approval
Execution Sandbox
```

### Principle 2 - RCA 必须绑定 Evidence

禁止：

```text
LLM says so
```

必须：

```text
RCA
 ↓
Evidence[]
```

### Principle 3 - Tool Output 不等于 Context

所有 Tool Result 都必须有长度与可信度边界。

### Principle 4 - Skills 与 Memory 分离

```text
Skill  = 应该怎么做
Memory = 以前怎么做过
```

### Principle 5 - Remediation 必须 Verify

执行成功不能直接把 Incident 标记成解决。

### Principle 6 - Agent 行为必须可审计

每次 Tool Call、Approval、Action、Verification 都要形成可持久化 Event。

---

## 16. 推荐的下一阶段优先级

当前不建议立即进入 Remediation。

推荐顺序：

```text
1. 完成当前 V1/V2 核心质量评估
   ↓
2. Incident / Evidence Domain Model
   ↓
3. Prometheus + Logs + Trace + GitHub Read-only Tools
   ↓
4. Evidence-grounded Deep Investigation
   ↓
5. SKILL.md
   ↓
6. Incident Memory
   ↓
7. Agent Evaluation
   ↓
8. Guarded Remediation
```

其中最关键的第一个架构里程碑不是自动执行命令，而是：

> **让 StackWatch 能从一个异常堆栈出发，自动关联 Logs、Trace、Metrics、Code 和 Deployment，形成可验证的 Evidence-grounded RCA。**

---

## 17. 最终定位

StackWatch 不应该成为：

> 一个会调用 `kubectl` 的 ChatGPT。

应该成为：

```text
                StackWatch
                    │
          Incident Intelligence
                    │
        ┌───────────┼───────────┐
        │           │           │
   Fingerprint    Skills      Memory
        │           │           │
        └───────────┼───────────┘
                    │
              Investigation Agent
                    │
                  Tools
                    │
                 Evidence
                    │
                   RCA
                    │
                 Policy
                    │
                Approval
                    │
              Remediation
                    │
                 Verify
```

最终差异化可以概括为：

> **Signal reduction first, Agent investigation second, remediation last.**

即：

1. 先用指纹、聚类、语义缓存压缩错误信号。
2. 只有真正的新问题和复杂问题才进入 Agent Investigation。
3. Agent RCA 必须由生产 Evidence 支撑。
4. 在调查质量和 Eval 稳定以后，再进入受控 Remediation。

这条路线既能延续 StackWatch 当前已有的技术资产，也能吸收现代 AI SRE 项目的成熟设计，而不会失去项目自身的 JVM/Application-first 定位。

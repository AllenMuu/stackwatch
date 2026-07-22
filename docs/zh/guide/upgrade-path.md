---
title: 升级路径
---

# 升级路径

原则：**渐进式增强**，每一步可独立交付价值，不破坏已跑通的链路。

## 当前状态（v0.1.0-SNAPSHOT）

五层主链路 + 两层横切均已实现。L2 / Kafka 默认关闭，按配置解锁。

| 层 | 状态 |
|----|------|
| domain 数据结构（不可变 record） | 已完成 |
| 预处理层 - 指纹生成 | 已完成 |
| 分析层 - L1 缓存 + L2 向量归并 + L3 LLM | 已完成 |
| 上下文优化（ContextOptimizer） | 已完成 |
| 采集层 - Logback + HTTP + Kafka | 已完成 |
| 聚合层 / 投递层 - 激增检测 + 飞书 | 已完成 |
| 横切 - Micrometer 度量 + 反馈飞轮 | 已完成 |

## 演进路线

### V1.1 - token 度量真实化

将 `ChatResponse` usage 元数据接入 `token_cost_total`，让成本阶梯有真实数据支撑。

### V1.2 - L2 真实启用（PgVector）

待 Spring AI 支持 `float[]` query 后，`PgVectorClusterRepository.findSimilar` 从内存余弦切换为真实向量检索。

### V1.3 - Kafka 真实启用

启用 Kafka 采集层，生产环境异步削峰。

### V1.4 - Redis L1 缓存

用 Redis 替换 Caffeine，实现跨实例共享与持久化。

### V2.x - 知识库积累

将确认的根因积累为可搜索的知识库，实现对已知错误模式的零 LLM 解决。

### V3.x - 多语言支持

扩展 Java 之外，支持 Python 和 Go 堆栈跟踪。

---
title: 选型分析
---

# 选型分析

选型原则：**满足当前需求 + 预留演进空间**（YAGNI + 可扩展）。

## LLM 框架：Spring AI vs LangChain4j

| 维度 | Spring AI | LangChain4j |
|------|-----------|-------------|
| Spring 生态 | 原生 | 需手动桥接 |
| 结构化输出 | `entity(Class)` 一行搞定 | 需自己解析 |
| Function Calling | `@Tool` 注解 | ToolSpecification |
| 向量库 | 统一 `VectorStore` 抽象 | API 分散 |

**决策：Spring AI** - 原生 autoconfig、`entity(RootCauseAnalysis.class)` 结构化输出、`@Tool` Function Calling。

## 向量库：PgVector vs Milvus vs ES

**决策：PgVector** - 簇规模万级够用。关键优势：周报要 JOIN 簇表和反馈表，PgVector 能在一个库做向量检索 + 关系 JOIN。

## L1 缓存：Caffeine vs Redis

**决策：Caffeine（MVP）-> Redis（生产）** - 零依赖、微秒级命中。接口已抽象（`FingerprintCache`），切换成本低。

## 消息队列：Kafka vs Redis Stream vs 内存

**决策：内存（MVP）-> Kafka（生产）** - Kafka 代码就绪（`@ConditionalOnProperty` 默认关闭）。选 Kafka 因削峰能力更强 + 重放能力。

## LLM 模型：DashScope vs DeepSeek vs OpenAI

**决策：DashScope（默认）** - OpenAI 兼容协议，可切换 DeepSeek 或 OpenAI。环境变量配置：`DASHSCOPE_API_KEY`、`LLM_BASE_URL`、`LLM_CHAT_MODEL`。

## 技术栈总览

| 领域 | 选型 |
|------|------|
| 框架 | Spring Boot 4.1 + Java 21 |
| LLM | Spring AI 2.0（OpenAI 兼容） |
| L1 缓存 | Caffeine（生产可换 Redis） |
| L2 向量 | PgVector（默认关闭） |
| 消息队列 | Kafka（默认关闭） |
| 弹性 | Resilience4j |
| 度量 | Micrometer + Prometheus |

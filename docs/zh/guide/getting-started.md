---
title: 快速上手
---

# 快速上手

## 环境要求

- **JDK 21+** - Spring Boot 4.1 + Spring AI 2.0 强制要求（不支持 Java 8/11/17）
- **Maven 3.6+**

## 运行

```bash
# 1. 配置 LLM API Key（走环境变量，禁止写入代码）
export DASHSCOPE_API_KEY=sk-...

# 2. 构建
mvn clean package

# 3. 运行
mvn spring-boot:run

# 4. 试用：输入异常堆栈，获取 LLM 根因
curl -X POST http://localhost:8080/analyze \
  -H "Content-Type: application/json" \
  -d '{"appName":"order-service","exceptionType":"NullPointerException","exceptionMessage":"Cannot invoke method on null","stackTrace":["com.foo.OrderService.process(OrderService.java:42)","com.foo.OrderController.handle(OrderController.java:17)"]}'
```

## 数据流

异常经过五层流水线处理：

1. **采集层** 通过 HTTP 接收错误事件
2. **指纹器** 生成 SHA-256 指纹（含框架帧过滤）
3. **分析层** 执行三层级联：L1 缓存未命中 -> L2 向量归并（如启用）-> L3 LLM 根因
4. 结果落入簇仓库，标记为 `AnalysisPath.LLM_NEW`
5. **聚合层** 进行激增检测和周 Top-N 聚合
6. **投递层** 如已配置，推送飞书告警

## 启用 L2（PgVector）

L2 默认关闭。启用需要同步修改三处：

1. `pom.xml` - 取消注释 PgVector starter
2. `application.yml` - 从 `spring.autoconfigure.exclude` 移除 `PgVectorStoreAutoConfiguration`
3. 设置 `stackwatch.l2.enabled=true` 并配置 `spring.datasource`

详见 [架构设计](/zh/guide/architecture) 了解完整的 L1/L2/L3 级联设计。

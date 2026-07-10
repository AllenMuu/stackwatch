# StackWatch

> AI 驱动的 Java 生产错误根因分析：异常堆栈指纹归并 + LLM 根因定位 + 定时周报聚合。

将生产异常堆栈自动投喂 LLM 进行根因定位与分类归并，结合定时任务按周聚合高频错误并推送飞书周报。目标：生产故障平均定位耗时缩短约 40%，高频问题发现周期由天级缩短至小时级。

## 文档

| 文档 | 内容 |
|------|------|
| [详细设计](docs/详细设计.md) | 架构详设、模块设计、核心流程、数据结构、表结构、接口、配置 |
| [选型分析](docs/选型分析.md) | 每个技术选型的备选方案、对比维度、决策与理由 |
| [升级路径](docs/升级路径.md) | 当前状态、后续演进路线（V1.x → V3.x）、优先级建议 |
| [面试准备](docs/面试准备-架构卖点与选型.md) | 8 卖点、14 选型问答、简历措辞打磨、高危追问应对 |

## 架构总览

五层主链路 + 两层横切：

```
① 采集层 Collector       -> Kafka（MVP：内存队列）
② 预处理层 Preprocessor  -> 指纹去重 + 采样
③ 分析层 Analyzer        -> L1 指纹 / L2 向量 / L3 LLM 三层级联（核心）
④ 聚合层 Aggregator      -> 实时激增检测 + 按周聚合
⑤ 投递层 Notifier        -> 飞书实时告警 + 飞书周报

横切 A: 度量层 Metrics   -> 定位耗时 / 准确率 / token 成本
横切 B: 反馈层 Feedback  -> 研发反馈回流 few-shot，越用越准
```

### 分析层三层归并（核心）

```
L1 指纹精确命中缓存   -> 0 token
L2 向量近似归并       -> 0 token
L3 LLM 簇代表根因分析 -> 真正调 LLM（仅占异常总量约 1%）
```

成本阶梯：L1 ≈ 0 -> L2 ≈ 0 -> L3 才花钱。

## 环境要求

- **JDK 17+**（Spring Boot 3.4 + Spring AI 1.0 强制要求，不支持 Java 8/11）
- Maven 3.6+

若 `mvn -v` 显示的 Java 版本非 17，构建与运行前需切换：

```bash
# macOS 查看已安装 JDK
/usr/libexec/java_home -V
# 指定 JDK 17（建议写入 ~/.zshrc 持久化）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

## 快速开始

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

## 当前状态（MVP）

- ✅ domain 数据结构（不可变 record）
- ✅ ② 预处理层：指纹生成（SHA-256 + 框架帧过滤 + 版本化 + 可解释记录）
- ✅ ③ 分析层：L1 缓存（Caffeine）+ L3 LLM 根因（Spring AI 结构化输出 + Function Calling）
- 🚧 L2 向量归并：接口已定义，待接入 PgVector
- 🚧 ① 采集层：待接入 Kafka / Logback Appender
- 🚧 ④ ⑤ 聚合层 / 投递层：待实现飞书周报
- 🚧 横切 A/B：待接入 Micrometer + 反馈闭环

## 技术栈

- Spring Boot 3.4 + Java 17
- Spring AI 1.0（OpenAI 兼容协议，DashScope/DeepSeek 可切换）
- Caffeine（L1 缓存）
- PgVector（L2 向量，待接入）

## 开源致谢

本项目借鉴了以下优秀开源项目的设计思想：

- **PostHog** error_tracking：指纹算法版本化、embedding rendering 元数据、周报结构
- **Arvo-AI/aurora**：RCA 后动作自动化、知识库积累思想
- **salesforce/PyRCA**：RCA 评估方法论

## 许可证

MIT

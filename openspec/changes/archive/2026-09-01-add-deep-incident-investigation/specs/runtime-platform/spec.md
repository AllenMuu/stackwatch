## MODIFIED Requirements

### Requirement: 零基础设施默认启动

默认配置下,StackWatch MUST 能在无数据库、无 Kafka、无向量库的环境下成功启动。`application.yml` 的 `spring.autoconfigure.exclude` MUST 包含 `DataSourceAutoConfiguration`、`PgVectorStoreAutoConfiguration`、`KafkaAutoConfiguration` 在 Spring Boot 4.1.0 / Spring AI 2.0.0 下等价且有效的全限定名(原 3.4 包路径已因 4.0 autoconfigure 模块重组而失效)。`stackwatch.incident.enabled` MUST 默认是 `false`，且 Incident 功能 MUST 不要求 L2 启用。

#### Scenario: 无基础设施启动成功

- **WHEN** 未配置数据库 / Kafka / 向量库,且 `stackwatch.l2.enabled=false`、`stackwatch.collector.kafka.enabled=false`、`stackwatch.incident.enabled=false`
- **THEN** 应用 MUST 成功启动,`/actuator/health` MUST 返回 UP

#### Scenario: autoconfigure exclude 类名核对

- **WHEN** Spring Boot 升级到 4.1.0
- **THEN** `spring.autoconfigure.exclude` 列表中每一项 MUST 在 4.1.0 / Spring AI 2.0.0 下为有效的 autoconfiguration 类全限定名;原 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 等旧路径已失效,MUST 更新为 4.1 下等价全限定名

#### Scenario: 独立启用 Incident PostgreSQL

- **WHEN** 配置 Incident PostgreSQL datasource 并将 `stackwatch.incident.enabled=true`
- **THEN** Flyway MUST 管理 `stackwatch_incident` schema，且 Incident 功能 MUST 在 `stackwatch.l2.enabled=false` 时仍可启用

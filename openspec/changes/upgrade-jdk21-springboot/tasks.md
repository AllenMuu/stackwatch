## 1. 前置核实(PRE-GATE-1)— 已完成(2026-07-10 联网)

- [x] 1.1 核实 Spring Boot 4.1.0 GA(2026-06-10,GitHub releases 确认;parent pom `<java.version>17</java.version>`,JDK 基线 17,支持 21)
- [x] 1.2 核实 Spring AI 2.0.0 GA(2026-06-12),官方基线即 Spring Boot 4.1.0(release notes "Upgrade to Spring Boot 4.1.0")
- [x] 1.3 确认无对应 Spring Boot 4.0.x 的 Spring AI 版本(1.x 基线 3.5.15,2.0 基线 4.1.0)
- [x] 1.4 锁定版本组合:JDK 21 + Spring Boot 4.1.0 + Spring AI 2.0.0(用户确认推荐方案,无需降级)
- [x] 1.5 核实 `autoconfigure.exclude` 三项类原包路径在 4.0.7 已 404(模块结构重组),Task 5 须查新全限定名
- [x] 1.6 核实 4.0 release notes breaking:tomcat/jetty runtime 改为 starters、micrometer metrics starter 缺失、autoconfigure exclusions 类加载器变更

## 2. JDK 工具链切换

- [x] 2.1 `jenv local 21`(或等价命令)将项目 JDK 切换到 21
- [x] 2.2 `mvn -v` 确认 JDK 21 生效

## 3. pom 版本切换

- [x] 3.1 `pom.xml` 中 `java.version` 从 `17` 改为 `21`
- [x] 3.2 `spring-boot-starter-parent` 从 `3.4.0` 改为 `4.1.0`
- [x] 3.3 `spring-ai.version` 从 `1.0.0` 改为 `2.0.0`
- [x] 3.4 核对并补全 4.0 starter 重组:若 `spring-boot-starter-web` 需显式 tomcat runtime starter 则补;补全 micrometer metrics starter(精确 artifactId 查 4.0 迁移文档)
- [x] 3.5 运行 `mvn dependency:tree` 确认传递依赖解析正常,无冲突

## 4. 编译期 breaking 修复

- [x] 4.1 运行 `mvn clean compile` 收集编译错误
- [x] 4.2 适配 Spring AI 2.0 `ChatClient` API 签名变更(`ErrorAnalyzer.callLlm` 的 `.tools()` / `.entity()` / `.content()`);Spring AI 1.0 -> 2.0 跨大版本,查 2.0 upgrade notes 做最小改动
  - 结果:`ErrorAnalyzer.callLlm` 当前链式调用 `prompt().user().tools().call().entity()` 在 Spring AI 2.0 下仍可编译;`.tools(Object...)` 继续支持 `@Tool` POJO(见 upgrade notes)。实际编译错误来自 Spring Boot 4.1 包路径重组:`MeterRegistryCustomizer` 与 `RestTemplateBuilder` 迁移,已在 `MetricsConfig` / `NotifierConfig` 中修复。
- [x] 4.3 `grep -rn "javax\." src/main/java/` 确认无 Jakarta EE 迁移残留(仅 `Fingerprinter` 中的字符串字面量过滤前缀命中,非 import)
- [x] 4.4 确认 `mvn clean compile` 通过

## 5. autoconfigure.exclude 核对(高危,已确认模块重组)

- [ ] 5.1 查 `DataSourceAutoConfiguration` 在 Spring Boot 4.1.0 的新全限定名(原 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 路径已 404)
- [ ] 5.2 查 `PgVectorStoreAutoConfiguration` 在 Spring AI 2.0.0 的新全限定名(原 `org.springframework.ai.vectorstore.pgvector.autoconfigure` 路径待确认)
- [ ] 5.3 查 `KafkaAutoConfiguration` 在 Spring Boot 4.1.0 的新全限定名
- [ ] 5.4 更新 `application.yml` 的 `spring.autoconfigure.exclude` 列表为 4.1.0 / Spring AI 2.0.0 下等价且有效的全限定名
- [ ] 5.5 同步 `VectorStoreConfig` / `KafkaCollectConfig` Javadoc 里引用的类名(若有变动)

## 6. 零基础设施启动冒烟

- [ ] 6.1 不配置 `DASHSCOPE_API_KEY`、无 DB、无 Kafka,运行 `mvn spring-boot:run`
- [ ] 6.2 确认应用启动成功,无 autoconfigure 类名失效错误
- [ ] 6.3 `curl localhost:8080/actuator/health` 返回 `UP`
- [ ] 6.4 `curl localhost:8080/actuator/prometheus` 返回 prometheus 格式指标(验证 micrometer metrics starter 补全)
- [ ] 6.5 若 4.1 默认启用虚拟线程,验证 `LogbackErrorAppender` 的 `ThreadLocal` 递归守卫仍正常工作

## 7. 测试验证

- [ ] 7.1 运行 `mvn test -Dtest=ErrorAnalyzerUnitTest,FingerprinterTest`,确认全绿且不要求 `DASHSCOPE_API_KEY`
- [ ] 7.2 运行 `mvn test` 全量测试,确认集成测试在无 key 时自动 skip(`@EnabledIfEnvironmentVariable`)
- [ ] 7.3 (可选)配置 `DASHSCOPE_API_KEY` 后运行 `mvn test -Dtest=ErrorAnalyzerTest` 集成验证

## 8. 配置与文档同步

- [ ] 8.1 核对 `spring.ai.openai.*` 配置键在 Spring AI 2.0 下是否保持(若有变更则同步 `application.yml`)
- [ ] 8.2 更新 `CLAUDE.md` 中 "JDK 17 is mandatory" 为 "JDK 21 is mandatory";Spring Boot 3.4 -> 4.1、Spring AI 1.0 -> 2.0 表述
- [ ] 8.3 核对 `README.md`(若有)的 JDK / 构建要求说明并同步

## 9. 验收与提交

- [ ] 9.1 运行 `mvn clean package` 全量通过
- [ ] 9.2 审查 diff:仅含版本跃迁 + 兼容修复 + starter 补全,不含以使用 JDK 21 新语法为目的的现有代码重构
- [ ] 9.3 确认 `PgVectorClusterRepository.findSimilar` 仍使用内存余弦相似度,未改为 `VectorStore.similaritySearch`
- [ ] 9.4 单一 commit,遵循 Conventional Commits 格式(如 `chore(build): upgrade to jdk 21, spring boot 4.1 and spring ai 2.0`)
- [ ] 9.5 PR 描述包含:版本变更前后对比、PRE-GATE-1 核实结果、autoconfigure.exclude 核对结果、starter 补全情况、测试结果

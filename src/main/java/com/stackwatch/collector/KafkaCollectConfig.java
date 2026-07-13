package com.stackwatch.collector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka 采集通道装配：①采集层 Kafka 通道总开关 @Configuration。
 *
 * <p>仅在 {@code stackwatch.collector.kafka.enabled=true} 时激活。
 * 激活前置（由主代理在 pom.xml / application.yml 落地）：
 * <ul>
 *   <li>pom.xml 引入 {@code org.springframework.kafka:spring-kafka}
 *       （Spring Boot 3.4 parent 已管理版本，无需显式 version）。</li>
 *   <li>{@code spring.autoconfigure.exclude} 移除
 *       {@code org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration}，
 *       否则 {@link org.springframework.kafka.core.KafkaTemplate} bean 不会被装配，
 *       {@link KafkaErrorProducer} 启动期注入失败。</li>
 *   <li>{@code spring.kafka.bootstrap-servers} 指向可用 broker。</li>
 * </ul>
 *
 * <p>本类作为占位 @Configuration 存在，集中承载 Kafka 通道未来扩展点：
 * <ul>
 *   <li>自定义 {@link org.springframework.kafka.core.ProducerFactory} 调优
 *       （acks / retries / batch.size / linger.ms）。</li>
 *   <li>自定义 {@link org.springframework.kafka.core.ConsumerFactory}
 *       （max.poll.records / enable.auto.commit）。</li>
 *   <li>{@code DeadLetterPublishingRecoverer} + {@code DefaultErrorHandler}
 *       毒丸消息死信路由。</li>
 *   <li>{@code ProducerListener} 监听发送成功/失败指标，对接 ⑤监控层。</li>
 * </ul>
 *
 * <p>{@link com.fasterxml.jackson.databind.ObjectMapper} 由 spring-boot-starter-web
 * 默认通过 {@code JacksonAutoConfiguration} 提供（含 {@code JavaTimeModule}），
 * 无需在此重复声明，避免与 web 层 ObjectMapper 实例冲突。
 *
 * <p>对应架构层：①采集层 Kafka 通道装配入口。
 */
@Configuration
@ConditionalOnProperty(name = "stackwatch.collector.kafka.enabled", havingValue = "true")
public class KafkaCollectConfig {

    /**
     * 占位构造：当前无额外 bean 需要显式声明（Producer/Consumer 各自用
     * {@code @Component} + {@code @ConditionalOnProperty} 独立装配）。
     * 生产化演进时在此注入 {@code ProducerFactory} / {@code ConsumerFactory}
     * 覆盖默认配置做细粒度调优。
     */
}

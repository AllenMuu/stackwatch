package com.stackwatch.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwatch.domain.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka 错误事件生产者：①采集层 Kafka 通道（生产化削峰/解耦/重放）。
 *
 * <p>默认关闭（{@code stackwatch.collector.kafka.enabled=false}），启用时把
 * {@link ErrorEvent} 序列化为 JSON 投递到 Kafka topic，由
 * {@link KafkaErrorConsumer} 异步消费驱动 ③分析层
 * {@link com.stackwatch.analyzer.ErrorAnalyzer#analyze(ErrorEvent)}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>异步发送（{@link KafkaTemplate#send} 返回 {@link CompletableFuture}），
 *       不阻塞采集线程。</li>
 *   <li>发送失败仅记 warn 不抛异常——采集层永远不能让业务感知到故障。</li>
 *   <li>JSON 序列化复用 Spring 容器的 {@link ObjectMapper}
 *       （spring-boot-starter-web 默认通过 JacksonAutoConfiguration 提供，
 *       含 JavaTimeModule，可正确处理 {@code occurredAt} 的 {@link java.time.Instant}）。</li>
 *   <li>用 {@code event.eventId()} 作为 Kafka 消息 key，保证同事件落同分区
 *       （虽实际每次 eventId 唯一，保留语义以便未来按 appName 路由扩展）。</li>
 * </ul>
 *
 * <p>对应架构层：①采集层 Kafka 通道（与 Logback Appender + HTTP /collect 并列的第三入口）。
 *
 * <p>启用前置：pom.xml 已引入 {@code org.springframework.kafka:spring-kafka}，
 * 且 {@code spring.autoconfigure.exclude} 已移除 {@code KafkaAutoConfiguration}。
 */
@Component
@ConditionalOnProperty(name = "stackwatch.collector.kafka.enabled", havingValue = "true")
public class KafkaErrorProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaErrorProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${stackwatch.collector.kafka.topic:error-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * 异步发送错误事件到 Kafka。
     *
     * <p>不阻塞调用方：序列化失败立即 warn 返回；网络/ broker 失败通过
     * {@link CompletableFuture#whenComplete} 回调记录，不影响采集主链路。
     *
     * @param event 不可变错误事件，record 默认可被 Jackson 序列化
     */
    public void send(ErrorEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Kafka serialize failed for event {}: {}", event.eventId(), e.getMessage());
            return;
        }
        String key = event.eventId();
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Kafka send failed for event {} on topic {}: {}",
                    event.eventId(), topic, ex.getMessage());
            } else if (log.isDebugEnabled()) {
                log.debug("Kafka sent event {} to topic {} partition {} offset {}",
                    event.eventId(), topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
    }
}

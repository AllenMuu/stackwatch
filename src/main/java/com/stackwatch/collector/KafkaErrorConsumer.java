package com.stackwatch.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwatch.analyzer.ErrorAnalyzer;
import com.stackwatch.domain.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 错误事件消费者：①采集层 Kafka 通道下游 -> ③分析层。
 *
 * <p>反序列化 Kafka 消息 JSON -> {@link ErrorEvent}，并驱动
 * {@link ErrorAnalyzer#analyze(ErrorEvent)} 完成根因分析。
 *
 * <p>默认关闭，启用条件与 {@link KafkaErrorProducer} 一致
 * （{@code stackwatch.collector.kafka.enabled=true}）。
 *
 * <p>错误处理（显式 catch，不静默吞异常，但避免毒丸循环）：
 * <ul>
 *   <li>反序列化失败：记 warn 并跳过该消息（毒丸消息不阻塞队列，
 *       生产化演进时可补 {@code DeadLetterPublishingRecoverer} 转死信队列）。</li>
 *   <li>分析异常：记 error 不抛——避免 @KafkaListener 默认 ErrorHandler
 *       无限重投同一条消息形成消费死循环。</li>
 * </ul>
 *
 * <p>对应架构层：①采集层 Kafka 通道（消费侧）-> ②预处理层 -> ③分析层 L1/L2/L3 级联。
 */
@Component
@ConditionalOnProperty(name = "stackwatch.collector.kafka.enabled", havingValue = "true")
public class KafkaErrorConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConsumer.class);

    private final ErrorAnalyzer errorAnalyzer;
    private final ObjectMapper objectMapper;

    public KafkaErrorConsumer(ErrorAnalyzer errorAnalyzer, ObjectMapper objectMapper) {
        this.errorAnalyzer = errorAnalyzer;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费 Kafka 消息并触发根因分析。
     *
     * <p>topic 由 {@code ${stackwatch.collector.kafka.topic:error-events}} 注入，
     * 与 {@link KafkaErrorProducer} 投递的 topic 一致。
     *
     * @param payload ErrorEvent 的 JSON 序列化字符串
     */
    @KafkaListener(topics = "${stackwatch.collector.kafka.topic:error-events}")
    public void onMessage(String payload) {
        ErrorEvent event;
        try {
            event = objectMapper.readValue(payload, ErrorEvent.class);
        } catch (Exception e) {
            log.warn("Kafka deserialize failed, skipping message: {}", e.getMessage());
            return;
        }
        try {
            errorAnalyzer.analyze(event);
        } catch (Exception e) {
            log.error("Kafka consume analyze failed for event {}: {}",
                event.eventId(), e.getMessage(), e);
        }
    }
}

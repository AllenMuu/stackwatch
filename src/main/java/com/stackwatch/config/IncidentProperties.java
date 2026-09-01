package com.stackwatch.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Deep Path Incident 的有界运行配置：{@code stackwatch.incident.*}。
 *
 * <p>所有上限均在绑定阶段校验，非法值会拒绝启动，而不是静默放宽安全边界。</p>
 */
@Validated
@ConfigurationProperties(prefix = "stackwatch.incident")
public record IncidentProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("6") @Min(1) int maxSteps,
    @DefaultValue("3") @Min(1) int maxToolCalls,
    @DefaultValue("5s") Duration toolCallTimeout,
    @DefaultValue("30s") Duration totalTimeout
) {
    public IncidentProperties {
        if (toolCallTimeout == null || toolCallTimeout.isZero() || toolCallTimeout.isNegative()) {
            throw new IllegalArgumentException("toolCallTimeout must be positive");
        }
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("totalTimeout must be positive");
        }
        if (totalTimeout.compareTo(toolCallTimeout) < 0) {
            throw new IllegalArgumentException("totalTimeout must not be shorter than toolCallTimeout");
        }
    }
}

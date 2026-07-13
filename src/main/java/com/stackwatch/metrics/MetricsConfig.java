package com.stackwatch.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 横切A度量层配置：给所有 stackwatch 指标统一打 {@code application=stackwatch} common tag。
 *
 * <p>与 {@code application.yml} 的 {@code management.metrics.tags.application=stackwatch}
 * 互为保障：yml 走 actuator 自动装配的 common tag；此处显式注册 {@link MeterRegistryCustomizer}
 * 作为代码级兜底——即使 yml 漏配 {@code management.metrics.tags.*}，也能保证 application tag 存在，
 * 便于多实例/多服务在 Prometheus 中区分来源。
 *
 * <p>对应架构层：横切A度量层。
 *
 * <p>依赖前提：{@code MeterRegistryCustomizer} 位于 spring-boot-micrometer-metrics（Spring Boot 4.1 起从 spring-boot-actuator-autoconfigure 迁移至此），
 * {@link MeterRegistry} bean 由 spring-boot-micrometer-metrics 自动装配；
 * {@code /actuator/prometheus} 端点仍由 spring-boot-starter-actuator 提供；
 * micrometer-registry-prometheus 提供 {@code PrometheusMeterRegistry}。
 * 故 pom 需同时引入 spring-boot-micrometer-metrics 与 micrometer-registry-prometheus（actuator 端点按需引入）。
 */
@Configuration
public class MetricsConfig {

    /** common tag 名：与 actuator {@code management.metrics.tags.<name>} 约定一致。 */
    private static final String COMMON_TAG_APPLICATION = "application";
    /** common tag 值：与本仓 {@code spring.application.name=stackwatch} 对齐。 */
    private static final String APPLICATION_NAME = "stackwatch";

    /**
     * 给所有 {@link MeterRegistry} 中注册的指标追加 {@code application=stackwatch} common tag。
     *
     * <p>actuator 的 {@code MeterRegistryPostProcessor} 在装配每个 MeterRegistry 后会回调此 customizer，
     * 对 AnalysisMetrics 注册的 stackwatch.* 指标同样生效。
     *
     * @return 通用 common tag customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> applicationCommonTagCustomizer() {
        return registry -> registry.config().commonTags(COMMON_TAG_APPLICATION, APPLICATION_NAME);
    }
}

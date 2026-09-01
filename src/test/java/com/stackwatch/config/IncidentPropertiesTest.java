package com.stackwatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IncidentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(IncidentPropertiesConfiguration.class);

    @Test
    void defaultsToDisabledWithBoundedRuntimeLimits() {
        contextRunner.run(context -> {
            IncidentProperties properties = context.getBean(IncidentProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.maxSteps()).isEqualTo(6);
            assertThat(properties.maxToolCalls()).isEqualTo(3);
            assertThat(properties.toolCallTimeout()).hasSeconds(5);
            assertThat(properties.totalTimeout()).hasSeconds(30);
        });
    }

    @Test
    void rejectsNonPositiveRuntimeLimits() {
        ApplicationContextRunner invalidContext = contextRunner
            .withPropertyValues("stackwatch.incident.max-steps=0");

        invalidContext.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("max-steps")
                .hasStackTraceContaining("最小不能小于1");
        });
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        ApplicationContextRunner invalidContext = contextRunner
            .withPropertyValues("stackwatch.incident.tool-call-timeout=0s");

        invalidContext.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("toolCallTimeout");
        });
    }

    @Configuration
    @EnableConfigurationProperties(IncidentProperties.class)
    static class IncidentPropertiesConfiguration {
    }
}

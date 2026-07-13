package com.stackwatch.notifier;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 投递层配置：注册 RestTemplate bean（通过 RestTemplateBuilder.build）。
 * 供 {@link FeishuClient} 构造注入。
 *
 * 不在 StackWatchConfig 内追加（硬约束：不改现有 .java），独立成类。
 */
@Configuration
public class NotifierConfig {

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}

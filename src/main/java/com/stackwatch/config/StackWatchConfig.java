package com.stackwatch.config;

import com.stackwatch.preprocess.EmbeddingRendering;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.Fingerprinter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * StackWatch 配置：集中创建分析链路的协作 bean。
 */
@Configuration
@EnableConfigurationProperties({AnalysisProperties.class, CacheProperties.class})
public class StackWatchConfig {

    @Bean
    Fingerprinter fingerprinter(AnalysisProperties properties) {
        return new Fingerprinter(properties.fingerprintTopN());
    }

    @Bean
    EmbeddingService embeddingService(Fingerprinter fingerprinter) {
        // MVP 用 PLAIN 渲染（仅规范化栈帧），解锁 L2 后可 A/B WITH_ERROR_MESSAGE
        return new EmbeddingService(fingerprinter, EmbeddingRendering.PLAIN);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}

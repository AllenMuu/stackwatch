package com.stackwatch.config;

import com.stackwatch.notifier.FeishuProperties;
import com.stackwatch.preprocess.EmbeddingRendering;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.Fingerprinter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StackWatch 配置：集中创建分析链路的协作 bean。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AnalysisProperties.class, CacheProperties.class, ContextOptimizerProperties.class, FeishuProperties.class})
public class StackWatchConfig {

    @Bean
    Fingerprinter fingerprinter(AnalysisProperties properties) {
        return new Fingerprinter(properties.fingerprintTopN());
    }

    @Bean
    EmbeddingService embeddingService(Fingerprinter fingerprinter,
                                      ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        // MVP 用 PLAIN 渲染；ObjectProvider 在无 EmbeddingModel bean 时返回 null（L2 关闭）
        return new EmbeddingService(fingerprinter, EmbeddingRendering.PLAIN,
            embeddingModelProvider.getIfAvailable());
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}

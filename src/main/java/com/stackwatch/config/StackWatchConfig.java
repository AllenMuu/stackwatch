package com.stackwatch.config;

import com.stackwatch.notifier.FeishuProperties;
import com.stackwatch.preprocess.EmbeddingRendering;
import com.stackwatch.preprocess.EmbeddingService;
import com.stackwatch.preprocess.CauseResolver;
import com.stackwatch.preprocess.ErrorNormalizer;
import com.stackwatch.preprocess.Fingerprinter;
import com.stackwatch.preprocess.MessageNormalizer;
import com.stackwatch.preprocess.StackFrameNormalizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * StackWatch 配置：集中创建分析链路的协作 bean。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    AnalysisProperties.class,
    CacheProperties.class,
    ContextOptimizerProperties.class,
    DataSourceProperties.class,
    FeishuProperties.class,
    FingerprintProperties.class,
    IncidentProperties.class
    ,ErrorHistoryProperties.class
})
public class StackWatchConfig {

    @Bean
    Fingerprinter fingerprinter(AnalysisProperties properties) {
        return new Fingerprinter(properties.fingerprintTopN());
    }

    @Bean
    CauseResolver causeResolver(FingerprintProperties properties) {
        return new CauseResolver(properties);
    }

    @Bean
    MessageNormalizer messageNormalizer(FingerprintProperties properties) {
        return new MessageNormalizer(properties);
    }

    @Bean
    StackFrameNormalizer stackFrameNormalizer(
        FingerprintProperties fingerprintProperties, AnalysisProperties analysisProperties) {
        return new StackFrameNormalizer(
            fingerprintProperties, analysisProperties.fingerprintTopN());
    }

    @Bean
    ErrorNormalizer errorNormalizer(
        CauseResolver causeResolver,
        MessageNormalizer messageNormalizer,
        StackFrameNormalizer stackFrameNormalizer) {
        return new ErrorNormalizer(causeResolver, messageNormalizer, stackFrameNormalizer);
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

    /**
     * Deep Path 的独立数据源。
     *
     * <p>全局仍排除 {@code DataSourceAutoConfiguration}，因此默认 Fast Path 不会创建
     * 数据源。只有 Incident 显式启用时才绑定标准 {@code spring.datasource.*} 配置，
     * 让 Flyway/JPA 在唯一的候选数据源上工作；这与 L2 开关相互独立。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
    @org.springframework.context.annotation.Primary
    DataSource incidentDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "incidentJdbcTemplate")
    @ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
    JdbcTemplate incidentJdbcTemplate(
        @org.springframework.beans.factory.annotation.Qualifier("incidentDataSource")
        DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "incidentTransactionManager")
    @ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
    PlatformTransactionManager incidentTransactionManager(
        @org.springframework.beans.factory.annotation.Qualifier("incidentDataSource")
        DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** Error history owns a separate lifecycle and is never created on the default fast path. */
    @Bean
    @ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled", havingValue = "true")
    DataSource errorHistoryDataSource(ErrorHistoryProperties properties) {
        return org.springframework.boot.jdbc.DataSourceBuilder.create()
            .url(properties.datasource().url()).username(properties.datasource().username())
            .password(properties.datasource().password()).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled", havingValue = "true")
    JdbcTemplate errorHistoryJdbcTemplate(
        @org.springframework.beans.factory.annotation.Qualifier("errorHistoryDataSource")
        DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "errorHistoryTransactionManager")
    @ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled", havingValue = "true")
    PlatformTransactionManager errorHistoryTransactionManager(
        @org.springframework.beans.factory.annotation.Qualifier("errorHistoryDataSource")
        DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(prefix = "stackwatch.error-history", name = "enabled", havingValue = "true")
    org.flywaydb.core.Flyway errorHistoryFlyway(
        @org.springframework.beans.factory.annotation.Qualifier("errorHistoryDataSource") DataSource dataSource) {
        return org.flywaydb.core.Flyway.configure().dataSource(dataSource)
            .schemas("stackwatch_error_history").createSchemas(true)
            .locations("classpath:db/error-history").load();
    }
}

package com.stackwatch.incident.runtime;

import com.stackwatch.config.IncidentProperties;
import com.stackwatch.incident.repository.IncidentRepository;
import com.stackwatch.incident.skills.IncidentSkillLoader;
import com.stackwatch.incident.skills.SkillMatcher;
import com.stackwatch.incident.toolset.GitDeploymentStubAdapter;
import com.stackwatch.incident.toolset.LogsStubAdapter;
import com.stackwatch.incident.toolset.ToolExecutor;
import com.stackwatch.incident.toolset.ToolRegistry;
import com.stackwatch.incident.toolset.TraceStubAdapter;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires the opt-in local-first runtime; no Incident beans are created when the flag is disabled. */
@Configuration
@ConditionalOnProperty(prefix = "stackwatch.incident", name = "enabled", havingValue = "true")
public class IncidentRuntimeConfiguration {

    @Bean
    IncidentSkillLoader incidentSkillLoader() {
        return new IncidentSkillLoader();
    }

    @Bean
    SkillMatcher incidentSkillMatcher(IncidentSkillLoader loader) {
        return new SkillMatcher(loader.loadDefaults());
    }

    @Bean
    ToolRegistry incidentToolRegistry() {
        return new ToolRegistry(List.of(new LogsStubAdapter(), new TraceStubAdapter(),
            new GitDeploymentStubAdapter()));
    }

    @Bean
    ToolExecutor incidentToolExecutor(ToolRegistry registry) {
        return new ToolExecutor(registry);
    }

    @Bean
    AgentDecisionProvider incidentAgentDecisionProvider(ChatClient chatClient) {
        return new LlmAgentDecisionProvider(chatClient);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService incidentToolCallExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stackwatch-incident-tool");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    DeepInvestigationRuntime deepInvestigationRuntime(IncidentRepository repository,
                                                      SkillMatcher skillMatcher,
                                                      IncidentSkillLoader loader,
                                                      ToolExecutor toolExecutor,
                                                      AgentDecisionProvider provider,
                                                      IncidentProperties properties,
                                                      ExecutorService toolCallExecutor) {
        return new DeepInvestigationRuntime(repository, skillMatcher, loader.loadDefaults(), toolExecutor,
            provider, properties, toolCallExecutor, java.time.Clock.systemUTC());
    }

    @Bean
    Executor incidentInvestigationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("stackwatch-incident-");
        executor.initialize();
        return executor;
    }

    @Bean
    InvestigationScheduler investigationScheduler(DeepInvestigationRuntime runtime,
                                                  @Qualifier("incidentInvestigationExecutor") Executor executor) {
        return new InvestigationScheduler(runtime, executor);
    }

    @Bean
    IncidentEscalator incidentEscalator(IncidentRepository repository, InvestigationScheduler scheduler,
                                        @Qualifier("incidentInvestigationExecutor") Executor executor) {
        return new PostgresIncidentEscalator(repository, scheduler, executor);
    }
}

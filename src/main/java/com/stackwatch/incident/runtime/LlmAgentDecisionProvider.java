package com.stackwatch.incident.runtime;

import com.stackwatch.incident.domain.AgentDecision;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;

/** Spring AI provider that requests only a structured decision summary. */
public final class LlmAgentDecisionProvider implements AgentDecisionProvider {

    private final ChatClient chatClient;

    public LlmAgentDecisionProvider(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient is required");
    }

    @Override
    public Optional<AgentDecision> nextDecision(InvestigationContext context) {
        Objects.requireNonNull(context, "context is required");
        String prompt = "Return exactly one JSON AgentDecision with decisionType, summary, toolset, and inputs. "
            + "Use only a short decision summary; never return chain-of-thought. "
            + "Incident=" + context.incident().activeKey()
            + "; observations=" + context.observations().size()
            + "; steps=" + context.stepCount()
            + "; toolCalls=" + context.toolCallCount();
        try {
            AgentDecision decision = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(AgentDecision.class);
            return Optional.ofNullable(decision);
        } catch (RuntimeException exception) {
            throw new AgentDecisionProviderException("LLM decision provider failed", exception);
        }
    }

    /** Runtime-visible provider failure without retaining provider response content. */
    public static final class AgentDecisionProviderException extends RuntimeException {
        public AgentDecisionProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.stackwatch.analyzer;

import com.stackwatch.config.ContextOptimizerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 上下文优化器：Prompt 入参与 @Tool 返回值的截断防线。
 *
 * <p>对应「企业级 LLM 微服务架构」上下文优化层启示——工具调用结果（异常堆栈、MDC、
 * 链路追踪日志）在塞进 LLM 上下文前必须经过一层过滤，否则上下文窗口易爆满 + 触发幻觉。
 *
 * <p>两道防线：
 * <ul>
 *   <li>{@link #optimizePromptVars(Map)}：截断 {@code callLlm} 组装的
 *       {@code exceptionMessage} / {@code mdc}（由 {@link ErrorAnalyzer#callLlm} 调用）；</li>
 *   <li>{@link #truncateToolResult(String, String)}：截断 @Tool 返回值
 *       （由 {@link AnalysisTools} 每个 @Tool 在 return 前调用）。
 *       {@code queryTraceContext} 接真实 SkyWalking/ARMS 后，单次 trace 日志轻松过万字符，
 *       没有这层兜底会直接吃掉 LLM 上下文。</li>
 * </ul>
 *
 * <p>当前只做「截断 + 标记原长度」（KISS）。信噪比过滤（折叠连续重复堆栈帧、去冗
 * Caused by 链）留作演进，见 {@code docs/agent-engineering-insights.md}。
 */
@Component
public class ContextOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ContextOptimizer.class);
    /** 截断后缀模板，附带原长度便于排查上下文丢失。 */
    private static final String TRUNCATED_SUFFIX_TEMPLATE = "...[truncated, original=%d chars]";

    private final ContextOptimizerProperties properties;

    public ContextOptimizer(ContextOptimizerProperties properties) {
        this.properties = properties;
    }

    /**
     * 截断 Prompt 模板变量中的长字段。返回新 Map，不修改入参（不可变）。
     *
     * <p>仅截断体积不可控字段：{@code exceptionMessage}（异常 message 可能含完整 SQL/响应体）、
     * {@code mdc}（可能携带长 JSON 报文）。其余字段已有上游精简或条数硬上限，不在此截断。
     */
    public Map<String, Object> optimizePromptVars(Map<String, Object> vars) {
        Map<String, Object> optimized = new HashMap<>(vars);
        Object exceptionMessage = vars.get("exceptionMessage");
        if (exceptionMessage instanceof String s && s.length() > properties.maxExceptionMessageLength()) {
            optimized.put("exceptionMessage", truncate(s, properties.maxExceptionMessageLength()));
        }
        Object mdc = vars.get("mdc");
        if (mdc instanceof String s && s.length() > properties.maxMdcLength()) {
            optimized.put("mdc", truncate(s, properties.maxMdcLength()));
        }
        return optimized;
    }

    /**
     * 截断 @Tool 返回值。每个 @Tool 在 return 前调用，集中兜底，未来接真实数据源时截断已就位。
     *
     * @param raw     工具原始返回值，可能为 null
     * @param toolName 工具名，仅用于日志定位哪个工具返回了超长结果
     * @return 截断后的结果；未超长或 null 时原样返回
     */
    public String truncateToolResult(String raw, String toolName) {
        if (raw == null || raw.length() <= properties.maxToolResultLength()) {
            return raw;
        }
        log.debug("Tool result truncated: tool={}, originalLen={}, maxLen={}",
            toolName, raw.length(), properties.maxToolResultLength());
        return truncate(raw, properties.maxToolResultLength());
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + String.format(TRUNCATED_SUFFIX_TEMPLATE, value.length());
    }
}

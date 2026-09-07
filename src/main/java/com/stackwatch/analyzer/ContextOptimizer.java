package com.stackwatch.analyzer;

import com.stackwatch.config.ContextOptimizerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 上下文优化器：Prompt 入参与 @Tool 返回值的 DoS 兜底防线。
 *
 * <p><b>定位是 DoS backstop，不是预算管控</b>：默认模型 qwen-plus 上下文 ~128k tokens，
 * 整个 prompt 仅数 k tokens，{@code exceptionMessage} / {@code mdc} 的字符级截断对预算无意义，
 * 紧截断反而会砍掉承载根因的字段（异常 message 尾部常是 SQL Detail / 响应体
 * error_description 等具体根因）。因此三字段共用一个很高的天花板
 * {@link ContextOptimizerProperties#maxFieldLength()}，正常输入永远触不到，只在病态输入
 * （如外部 {@code /collect} 塞入 5MB message）时兜底防爆窗口。
 *
 * <p>两道防线：
 * <ul>
 *   <li>{@link #optimizePromptVars(Map)}：对 {@code callLlm} 组装的
 *       {@code exceptionMessage} / {@code mdc} 做统一兜底（由 {@link ErrorAnalyzer#callLlm} 调用）；</li>
 *   <li>{@link #truncateToolResult(String, String)}：对 @Tool 返回值兜底
 *       （由 {@link AnalysisTools} 每个 @Tool 在 return 前调用）。
 *       {@code queryTraceContext} 接真实 SkyWalking/ARMS 后，单次 trace 日志轻松过万字符，
 *       没有这层兜底会直接吃掉 LLM 上下文。</li>
 * </ul>
 *
 * <p>天花板被触到时采用 <b>head+tail</b> 截断（各 50%）：异常 message 与 MDC 的根因细节常在尾部，
 * 纯头截断会丢失。多行字段（{@code mdc} 由 {@link ErrorAnalyzer#callLlm} 渲染成
 * {@code key=value\n} 逐行）按整行对齐切，DoS 触发时输出仍可解析；单行字段回退为字符级 head+tail。
 *
 * <p>信噪比过滤（折叠连续重复堆栈帧、去冗 Caused by 链）留作演进，
 * 见 {@code docs/agent-engineering-insights.md}。
 */
@Component
public class ContextOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ContextOptimizer.class);
    /** head+tail 各占比例（0~1）。 */
    private static final double HEAD_RATIO = 0.5;
    /** 截断后缀模板，标注中间被省略 + 原长度，便于排查上下文丢失。 */
    private static final String TRUNCATED_SUFFIX_TEMPLATE = "...[truncated middle, original=%d chars]...";

    private final ContextOptimizerProperties properties;

    public ContextOptimizer(ContextOptimizerProperties properties) {
        this.properties = properties;
    }

    /**
     * 对 Prompt 模板变量中的体积不可控字段做 DoS 兜底。返回新 Map，不修改入参（不可变）。
     *
     * <p>仅兜底 {@code exceptionMessage}（异常 message 可能含完整 SQL/响应体）与
     * {@code mdc}（可能携带长 JSON 报文）。其余字段已有上游精简或条数硬上限，不在此兜底。
     */
    public Map<String, Object> optimizePromptVars(Map<String, Object> vars) {
        Map<String, Object> optimized = new HashMap<>(vars);
        Object exceptionMessage = vars.get("exceptionMessage");
        if (exceptionMessage instanceof String s && s.length() > properties.maxFieldLength()) {
            optimized.put("exceptionMessage", truncate(s));
        }
        Object mdc = vars.get("mdc");
        if (mdc instanceof String s && s.length() > properties.maxFieldLength()) {
            optimized.put("mdc", truncate(s));
        }
        return optimized;
    }

    /**
     * 对 @Tool 返回值做 DoS 兜底。每个 @Tool 在 return 前调用，集中兜底，未来接真实数据源时截断已就位。
     *
     * <p>注意：{@code queryTraceContext} 接通真实链路追踪后，trace 日志是时间序、故障时刻在尾部，
     * 应改为 <b>tail 偏置 + 整行截断</b>（见该 @Tool 的 TODO）。当前 head+tail 通用兜底对 trace
     * 不够精准，但作为防爆窗口的最后防线已足够。
     *
     * @param raw     工具原始返回值，可能为 null
     * @param toolName 工具名，仅用于日志定位哪个工具返回了超长结果
     * @return 截断后的结果；未超长或 null 时原样返回
     */
    public String truncateToolResult(String raw, String toolName) {
        if (raw == null || raw.length() <= properties.maxFieldLength()) {
            return raw;
        }
        log.debug("Tool result truncated: tool={}, originalLen={}, maxLen={}",
            toolName, raw.length(), properties.maxFieldLength());
        return truncate(raw);
    }

    /**
     * head+tail 截断：保留首 {@code HEAD_RATIO} + 末剩余，中间插省略标记。
     * 多行输入按整行对齐（head 退到上一个换行、tail 进到下一个换行），避免半行；
     * 单行输入无换行则回退为字符级 head+tail。
     */
    private String truncate(String value) {
        int maxLen = properties.maxFieldLength();
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        int headLen = (int) Math.round(maxLen * HEAD_RATIO);
        int tailLen = maxLen - headLen;
        String head = value.substring(0, headLen);
        String tail = value.substring(value.length() - tailLen);
        int headLastNl = head.lastIndexOf('\n');
        if (headLastNl >= 0) {
            head = head.substring(0, headLastNl);
        }
        int tailFirstNl = tail.indexOf('\n');
        if (tailFirstNl >= 0) {
            tail = tail.substring(tailFirstNl + 1);
        }
        return head + String.format(TRUNCATED_SUFFIX_TEMPLATE, value.length()) + tail;
    }
}

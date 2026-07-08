package com.stackwatch.analyzer;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * LLM Function Calling 工具：防幻觉的核心。
 * 对应 B1 设计：③分析层 L3。
 *
 * 让 LLM 查事实而非凭空猜根因：
 * - queryRecentChanges：判断是否近期改动引入
 * - querySimilarHistory：查历史已确认同类根因
 * - queryTraceContext：还原调用链上下文
 *
 * MVP：返回占位提示。解锁后接 GitLab API / 历史归因库 / 链路追踪。
 *
 * 注意：Spring AI @Tool 的注解路径与签名可能随版本变化，以官方文档为准。
 */
@Component
public class AnalysisTools {

    @Tool(description = "查询指定应用最近 24 小时的代码变更记录，用于判断异常是否由近期改动引入。返回变更文件、提交人、提交信息。")
    public String queryRecentChanges(String appName) {
        // TODO: 接入 GitLab/Git API 或部署记录
        return "[MVP 占位] 近期变更数据源未接入。请基于堆栈本身判断，不要编造变更记录。";
    }

    @Tool(description = "查询与指定异常类型+消息相似的历史已确认归因记录，返回根因和已验证的修复方案。仅返回研发标记为正确的样本。")
    public String querySimilarHistory(String exceptionType, String message) {
        // TODO: 接入历史归因库（few_shot_sample 表）
        return "[MVP 占位] 历史归因库未接入。若无历史样本，请基于堆栈本身分析。";
    }

    @Tool(description = "查询指定 traceId 关联的完整调用链日志，用于还原异常发生前后的上下文（上下游调用、参数、耗时）。")
    public String queryTraceContext(String traceId) {
        // TODO: 接入链路追踪（SkyWalking/ARMS）
        return "[MVP 占位] 链路追踪数据源未接入。";
    }
}

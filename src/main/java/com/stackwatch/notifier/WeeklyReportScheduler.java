package com.stackwatch.notifier;

import com.stackwatch.aggregator.WeeklyAggregator;
import com.stackwatch.aggregator.WeeklyReportData;
import com.stackwatch.domain.ErrorCluster;
import com.stackwatch.domain.RootCauseAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 周报调度器：⑤投递层。
 *
 * 每周一 9:00（Asia/Shanghai）触发：
 *   {@link WeeklyAggregator#aggregateWeekly} 聚合本周 Top 簇
 *   -> {@link ChatClient} 让 LLM 总结「Top3 + 趋势 + 建议」
 *   -> {@link FeishuClient#sendWebhook} 推送飞书。
 *
 * LLM 调用失败回退为原始数据文本（错误显式处理：try-catch + 日志 warn + 兜底文案，
 * 不静默吞异常，也不让周报因 LLM 故障而缺失）。
 *
 * 依赖 {@code @EnableScheduling}（见 existingFileChanges，在 StackWatchConfig 追加）。
 *
 * 注意：Spring AI ChatClient 的 .content() API 签名可能随版本变化，以官方文档为准。
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration WEEK = Duration.ofDays(7);

    private final WeeklyAggregator weeklyAggregator;
    private final FeishuClient feishuClient;
    private final ChatClient chatClient;

    public WeeklyReportScheduler(WeeklyAggregator weeklyAggregator,
                                 FeishuClient feishuClient,
                                 ChatClient chatClient) {
        this.weeklyAggregator = weeklyAggregator;
        this.feishuClient = feishuClient;
        this.chatClient = chatClient;
    }

    /**
     * 定时触发周报（周一 9:00）。同时作为 {@code ReportController} 手动触发的复用入口。
     */
    @Scheduled(cron = "0 0 9 * * MON")
    public void runWeeklyReport() {
        Instant end = Instant.now();
        Instant start = end.minus(WEEK);
        WeeklyReportData data = weeklyAggregator.aggregateWeekly(start, end);
        log.info("Weekly report aggregated: total={}, topSize={}", data.totalCount(), data.topClusters().size());

        String summary = summarize(data);
        feishuClient.sendWebhook(summary);
    }

    private String summarize(WeeklyReportData data) {
        String prompt = buildPrompt(data);
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            return (content == null || content.isBlank()) ? fallback(data) : content;
        } catch (Exception e) {
            log.warn("LLM weekly summary failed, fallback to raw: {}", e.getMessage());
            return fallback(data);
        }
    }

    private String buildPrompt(WeeklyReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 StackWatch 错误运维助手，请基于本周错误聚合数据生成周报。\n\n");
        sb.append("## 周期\n").append(format(data.periodStart()))
          .append(" ~ ").append(format(data.periodEnd())).append('\n');
        sb.append("## 总数\n本周累计错误次数：").append(data.totalCount()).append('\n');
        sb.append("## Top 簇\n");
        int idx = 1;
        for (ErrorCluster c : data.topClusters()) {
            RootCauseAnalysis a = c.analysis();
            String rootCause = (a == null) ? "（未分析）" : a.rootCause();
            String category = (a == null) ? "UNKNOWN" : a.category();
            sb.append(idx++).append(". ")
              .append(c.exceptionType()).append(" @ ").append(c.appName())
              .append(" | 次数=").append(c.memberCount())
              .append(" | 分类=").append(category)
              .append(" | 根因=").append(rootCause).append('\n');
        }
        sb.append("\n## 输出要求\n");
        sb.append("1. 本周 Top3 错误概述\n");
        sb.append("2. 趋势研判（新增 / 复发 / 环比）\n");
        sb.append("3. 下周建议（优先级排序的修复与排查动作）\n");
        sb.append("用中文，简洁，适合飞书群消息阅读。");
        return sb.toString();
    }

    /** LLM 失败 / 空返回时的兜底：原始数据文本，保证周报不缺失。 */
    private String fallback(WeeklyReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("[StackWatch 周报] ").append(format(data.periodStart()))
          .append(" ~ ").append(format(data.periodEnd())).append('\n');
        sb.append("本周累计错误：").append(data.totalCount()).append(" 次\n");
        sb.append("Top 簇：\n");
        int idx = 1;
        for (ErrorCluster c : data.topClusters()) {
            sb.append(idx++).append(". ").append(c.exceptionType())
              .append(" @ ").append(c.appName())
              .append("（").append(c.memberCount()).append(" 次）\n");
        }
        sb.append("（LLM 总结失败，已回退原始数据）");
        return sb.toString();
    }

    private String format(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE).format(FMT);
    }
}

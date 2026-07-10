package com.stackwatch.domain;

import java.time.Instant;

/**
 * Few-shot 样本：横切B反馈层产物，数据飞轮闭环的载体。
 *
 * <p>来源：研发在飞书卡片点【根因对/错】并补填正确根因，经
 * {@code /feedback} 回流到 few-shot 库（{@link com.stackwatch.feedback.FewShotRepository}）。
 *
 * <p>消费：{@code ErrorAnalyzer.callLlm} 按 exceptionType 召回最近 N 条样本，
 * 注入 root-cause prompt 的 {@code historicalSamples} 占位，作为 in-context 示例引导 LLM，
 * 使后续相似堆栈的根因判定向已确认的正确答案收敛（越用越准）。
 *
 * <p>不可变，遵循全局 immutability 原则。String/Instant 本身不可变，
 * compact constructor 仅做 null 归一化，避免下游 NPE（防御性兜底）。
 */
public record FewShotSample(
    String id,
    String clusterId,
    String exceptionType,
    String stackText,
    String correctRootCause,
    Instant createdAt
) {
    public FewShotSample {
        exceptionType = exceptionType == null ? "UNKNOWN" : exceptionType;
        stackText = stackText == null ? "" : stackText;
        correctRootCause = correctRootCause == null ? "" : correctRootCause;
    }
}

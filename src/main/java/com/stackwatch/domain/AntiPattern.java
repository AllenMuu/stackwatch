package com.stackwatch.domain;

import java.time.Instant;

/**
 * 负样本（误判模式）：横切B反馈层产物，数据飞轮负样本侧载体，与 {@link FewShotSample} 对偶。
 *
 * <p>来源：研发在飞书卡片点【根因错】并补填正确根因时，{@code /feedback} 携带 LLM 原误判根因
 * （{@code wrongRootCause}）+ 人工修正的正确根因（{@code correctRootCause}），回流到 anti-pattern 库
 * （{@link com.stackwatch.feedback.AntiPatternRepository}）。
 *
 * <p>消费：{@code ErrorAnalyzer.callLlm} 按 exceptionType 召回最近 N 条 anti-pattern，
 * 注入 root-cause prompt 的 {@code antiPatterns} 占位，作为 in-context 负向警示，
 * 提示 LLM「这类堆栈历史上误判为 X，实际是 Y」。与 few-shot 正样本（「该这么答」）对偶--
 * 正样本引导正确方向，负样本警示常见误判，合起来构成正负双向自学习飞轮。
 *
 * <p>灵感来源：支付宝 PagePilot 的 known-failures（失败模式库，按 errorCode 匹配，只增不删），
 * 在错误根因分析侧落地为「LLM 误判模式库」。与 few-shot（正样本，Aurora knowledge base grows 思想）对偶。
 *
 * <p>不可变，遵循全局 immutability 原则。compact constructor 仅做 null 归一化（防御性兜底）。
 */
public record AntiPattern(
    String id,
    String clusterId,
    String exceptionType,
    String stackText,
    String wrongRootCause,
    String correctRootCause,
    Instant createdAt
) {
    public AntiPattern {
        exceptionType = exceptionType == null ? "UNKNOWN" : exceptionType;
        stackText = stackText == null ? "" : stackText;
        wrongRootCause = wrongRootCause == null ? "" : wrongRootCause;
        correctRootCause = correctRootCause == null ? "" : correctRootCause;
    }
}

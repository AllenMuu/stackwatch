package com.stackwatch.feedback;

/**
 * 反馈请求 DTO：横切B反馈层 HTTP 入口（{@code POST /feedback}）。
 *
 * <p>来源：飞书卡片【根因对/错】回调，携带被反馈的簇 ID + 异常上下文 + 人工修正的正确根因。
 *
 * <p>校验约定：{@code correctRootCause} 为空（null 或纯空白）时，
 * {@link FeedbackController} 返回 400——反馈必须携带"正确答案"才有飞轮价值。
 *
 * <p>不可变，遵循全局 immutability 原则。
 */
public record FeedbackRequest(
    String clusterId,
    String exceptionType,
    String stackText,
    String correctRootCause
) {
}

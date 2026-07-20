package com.stackwatch.domain;

/**
 * 根因复核级别：③分析层 L3 后处理产物，借鉴 PagePilot 置信度分档门控思想。
 *
 * <p>三档策略（由 {@code ErrorAnalyzer.classifyReview} 基于 LLM 自评置信度 + 证据校验计算）：
 * <ul>
 *   <li>{@link #AUTO_CONFIRMED} - 高置信且有证据，自动归因，不打扰研发；</li>
 *   <li>{@link #NEEDS_CONFIRMATION} - 中置信（兜底阈值 &le; 置信度 &lt; 高置信阈值）且有证据，
 *       归因仍输出但标记「待确认」，驱动飞书卡片低介入确认，回流 few-shot；</li>
 *   <li>{@link #NEEDS_HUMAN_REVIEW} - 低置信 / 无证据 / LLM 失败，转人工排查。</li>
 * </ul>
 *
 * <p>与 {@link RootCauseAnalysis#needHumanReview()} 的关系：
 * {@code AUTO_CONFIRMED -> false}，其余两档 {@code -> true}。
 * L1/L2 复用历史根因时不重新判定，由 {@link #fromReviewFlag(boolean)} 从历史标志位近似推断。
 *
 * <p>灵感来源：支付宝 PagePilot 的置信度四档门控（静默执行 / 需确认 / 拒绝 / 无法解析），
 * 在错误根因分析垂直领域收敛为三档--根因分析不存在「拒绝执行」，中档统一为「带存疑求确认」。
 */
public enum ReviewLevel {
    AUTO_CONFIRMED,
    NEEDS_CONFIRMATION,
    NEEDS_HUMAN_REVIEW;

    /**
     * 从历史 {@code needHumanReview} 标志位近似推断复核级别（供 L1/L2 缓存复用路径使用）。
     *
     * <p>只能区分两档：未标复核 -> {@link #AUTO_CONFIRMED}，标复核 -> {@link #NEEDS_HUMAN_REVIEW}；
     * 历史的 {@link #NEEDS_CONFIRMATION} 落库时已被折叠为 {@code needHumanReview=true}，此处无法还原，
     * 这是缓存复用路径可接受的近似（L1/L2 本就不重新判定）。
     *
     * @param needHumanReview 历史根因的复核标志位
     * @return 近似复核级别，永不为 null
     */
    public static ReviewLevel fromReviewFlag(boolean needHumanReview) {
        return needHumanReview ? NEEDS_HUMAN_REVIEW : AUTO_CONFIRMED;
    }
}

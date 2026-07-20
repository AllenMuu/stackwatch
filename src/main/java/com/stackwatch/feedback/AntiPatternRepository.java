package com.stackwatch.feedback;

import com.stackwatch.domain.AntiPattern;

import java.util.List;

/**
 * 负样本（误判模式）仓储：横切B反馈层，与 {@link FewShotRepository} 对偶。
 *
 * <p>负样本飞轮闭环（与正样本 few-shot 飞轮对称）：
 * <pre>
 *   研发标【根因错】(save) ──► anti-pattern 库 ──► ErrorAnalyzer.callLlm(findByExceptionType) ──► prompt 负向警示注入
 *        ▲                                                                       │
 *        └──────── 飞书卡片【根因错】回调 ◄──── 相似堆栈再次出现 ◄─────────────────────────┘
 * </pre>
 *
 * <p>正样本 few-shot 告诉 LLM「该这么答」，负样本 anti-pattern 告诉 LLM「别那么答」，
 * 一正一反构成完整自学习闭环。灵感来源：PagePilot known-failures（失败模式库，只增不删）。
 *
 * <p>MVP：{@link InMemoryAntiPatternRepository} 内存实现，按异常类型分桶 + createdAt 倒序召回。
 * 生产演进：与 few-shot 同步接入向量库 / ES，按类型预筛 + 栈向量相似 Top-K 召回。
 */
public interface AntiPatternRepository {

    /**
     * 持久化一条误判模式负样本。
     *
     * @param antiPattern 误判模式；null 时静默跳过（防御性兜底，不应常态发生）
     */
    void save(AntiPattern antiPattern);

    /**
     * 按异常类型查最近 {@code limit} 条误判模式，用于 prompt 负向警示注入。
     *
     * @param exceptionType 异常全限定类名，如 {@code java.lang.NullPointerException}
     * @param limit         召回上限；非正数时由实现回退到默认值
     * @return 不可变列表，按 createdAt 倒序；无命中时返回空列表
     */
    List<AntiPattern> findByExceptionType(String exceptionType, int limit);
}

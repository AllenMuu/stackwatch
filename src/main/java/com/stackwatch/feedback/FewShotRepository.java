package com.stackwatch.feedback;

import com.stackwatch.domain.FewShotSample;

import java.util.List;

/**
 * Few-shot 样本仓储：横切B反馈层。
 *
 * <p>数据飞轮闭环：
 * <pre>
 *   研发反馈(save) ──► few-shot 库 ──► ErrorAnalyzer.callLlm(findByExceptionType) ──► prompt 注入
 *        ▲                                                                  │
 *        └──────────── 飞书卡片【根因对/错】回调 ◄──────── 相似堆栈再次出现 ◄──────┘
 * </pre>
 *
 * <p>MVP：{@link InMemoryFewShotRepository} 内存实现，按异常类型分桶 + createdAt 倒序召回。
 * 生产演进：接入向量库 / ES，按 exceptionType 预筛 + 栈向量相似 Top-K 召回，
 * 提升 few-shot 与当前堆栈的语义匹配度。
 */
public interface FewShotRepository {

    /**
     * 持久化一条人工确认的反馈样本。
     *
     * @param sample 反馈样本；null 时静默跳过（防御性兜底，不应常态发生）
     */
    void save(FewShotSample sample);

    /**
     * 按异常类型查最近 {@code limit} 条已确认样本，用于 prompt few-shot 注入。
     *
     * @param exceptionType 异常全限定类名，如 {@code java.lang.NullPointerException}
     * @param limit         召回上限；非正数时由实现回退到默认值
     * @return 不可变样本列表，按 createdAt 倒序；无命中时返回空列表
     */
    List<FewShotSample> findByExceptionType(String exceptionType, int limit);
}

package com.stackwatch.domain;

/**
 * 指纹算法版本。
 * 借鉴 PostHog FingerprintVersion：算法升级时，老簇保持老版本、新簇用新版本，
 * 避免算法升级导致历史归并关系全部断裂。
 *
 * 质量评估（借鉴 PostHog V2 注释）：上线新版本前，需用 LLM 标注的 pair 数据集
 * + pairwise F1 离线评估，确保聚合质量提升。
 */
public enum FingerprintVersion {
    /** V1：SHA-256 of top-N 规范化业务帧 + 异常类型。 */
    V1,
    /** V2（预留）：归一化 volatile 路径与消息 token，提升相似堆栈聚合率。 */
    V2
}

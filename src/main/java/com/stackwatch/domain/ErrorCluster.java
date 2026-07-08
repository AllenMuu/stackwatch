package com.stackwatch.domain;

import java.time.Instant;

/**
 * 错误簇：归并的产物。一个簇 = 一组相似异常 + 一次 LLM 归因。
 * 对应 B1 设计：③分析层 L2/L3 产出。
 *
 * embedding 为 float[]，通过 compact constructor + accessor 双重 defensive copy
 * 保证不可变（遵循全局 immutability 原则）。
 */
public record ErrorCluster(
    String clusterId,
    String representativeHash,
    String appName,
    String exceptionType,
    int memberCount,
    Instant firstSeen,
    Instant lastSeen,
    ClusterStatus status,
    RootCauseAnalysis analysis,
    float[] embedding
) {
    public ErrorCluster {
        embedding = embedding == null ? new float[0] : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    /** 新成员归入时递增计数（保持不可变，返回新实例）。 */
    public ErrorCluster increment(Instant now) {
        return new ErrorCluster(
            clusterId, representativeHash, appName, exceptionType,
            memberCount + 1, firstSeen, now, status, analysis, embedding
        );
    }

    /** 创建新簇。 */
    public static ErrorCluster newOne(String clusterId, String appName, String exceptionType,
                                      String representativeHash, Instant now,
                                      RootCauseAnalysis analysis, float[] embedding) {
        return new ErrorCluster(
            clusterId, representativeHash, appName, exceptionType,
            1, now, now, ClusterStatus.ANALYZED, analysis, embedding
        );
    }
}

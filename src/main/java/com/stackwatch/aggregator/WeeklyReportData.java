package com.stackwatch.aggregator;

import com.stackwatch.domain.ErrorCluster;

import java.time.Instant;
import java.util.List;

/**
 * 周报数据：④聚合层产出，⑤投递层消费。
 *
 * 不可变，topClusters 在 compact constructor 做 defensive copy
 * （遵循全局 immutability 原则，与 ErrorEvent / RootCauseAnalysis 一致）。
 */
public record WeeklyReportData(
    Instant periodStart,
    Instant periodEnd,
    List<ErrorCluster> topClusters,
    long totalCount
) {
    public WeeklyReportData {
        topClusters = (topClusters == null) ? List.of() : List.copyOf(topClusters);
    }
}

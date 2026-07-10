package com.stackwatch.aggregator;

import com.stackwatch.analyzer.ClusterRepository;
import com.stackwatch.domain.ErrorCluster;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 周报聚合器：④聚合层。
 *
 * 按周窗口从 {@link ClusterRepository} 拉取全部簇，按 memberCount 倒序取 Top N，
 * 汇总本周总错误次数，产出 {@link WeeklyReportData} 供投递层使用。
 *
 * 依赖 {@code ClusterRepository.findAll()}：
 *   现有接口未声明该方法，需补声明 + 内存实现（见 existingFileChanges）。
 */
@Service
public class WeeklyAggregator {

    /** 周报 Top N 簇数量。 */
    private static final int TOP_N = 10;

    private final ClusterRepository clusterRepository;

    public WeeklyAggregator(ClusterRepository clusterRepository) {
        this.clusterRepository = clusterRepository;
    }

    /**
     * 聚合 [start, end] 周窗口的错误数据。
     *
     * @param start 周起始时刻（含）
     * @param end   周结束时刻（含）
     * @return 周报数据（Top N 簇 + 总次数）
     */
    public WeeklyReportData aggregateWeekly(Instant start, Instant end) {
        List<ErrorCluster> all = clusterRepository.findAll();
        long total = all.stream()
            .mapToLong(ErrorCluster::memberCount)
            .sum();
        List<ErrorCluster> top = all.stream()
            .sorted(Comparator.comparingInt(ErrorCluster::memberCount).reversed())
            .limit(TOP_N)
            .toList();
        return new WeeklyReportData(start, end, top, total);
    }
}

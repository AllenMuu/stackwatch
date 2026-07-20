package com.stackwatch.feedback;

import com.stackwatch.domain.AntiPattern;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AntiPatternRepository} 的 MVP 内存实现：横切B反馈层，与 {@link InMemoryFewShotRepository} 对称。
 *
 * <p>存储模型：{@code ConcurrentHashMap<exceptionType, 同步 List>}。
 * <ul>
 *   <li>分桶键 = exceptionType，与召回查询维度一致，O(1) 定位桶；</li>
 *   <li>桶内用同步 List，兼容飞书卡片回调并发写入；</li>
 *   <li>召回时对桶做快照后排序，遵循同步 List「迭代需外部同步」契约。</li>
 * </ul>
 *
 * <p>注意：进程重启即丢，MVP 阶段够用；生产换持久化向量库 / ES，
 * 召回从「按类型最近 N 条」升级为「类型预筛 + 栈向量相似 Top-K」。
 */
@Component
public class InMemoryAntiPatternRepository implements AntiPatternRepository {

    /** limit 入参非法（<=0）时的回退上限，与 prompt anti-pattern 注入条数对齐。 */
    private static final int DEFAULT_LIMIT_FALLBACK = 3;

    private final Map<String, List<AntiPattern>> storeByExceptionType = new ConcurrentHashMap<>();

    @Override
    public void save(AntiPattern antiPattern) {
        if (antiPattern == null) {
            return;
        }
        // AntiPattern 紧凑构造已把 null exceptionType 归一为 "UNKNOWN"，此处直接用作分桶键
        String key = antiPattern.exceptionType();
        List<AntiPattern> bucket = storeByExceptionType.computeIfAbsent(
            key, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        bucket.add(antiPattern);
    }

    @Override
    public List<AntiPattern> findByExceptionType(String exceptionType, int limit) {
        if (exceptionType == null) {
            return List.of();
        }
        List<AntiPattern> bucket = storeByExceptionType.get(exceptionType);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<AntiPattern> snapshot;
        synchronized (bucket) {
            snapshot = new ArrayList<>(bucket);
        }
        int safeLimit = limit > 0 ? limit : DEFAULT_LIMIT_FALLBACK;
        return snapshot.stream()
            .sorted(Comparator.comparing(AntiPattern::createdAt).reversed())
            .limit(safeLimit)
            .toList();
    }
}

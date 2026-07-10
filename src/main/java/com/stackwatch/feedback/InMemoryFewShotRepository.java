package com.stackwatch.feedback;

import com.stackwatch.domain.FewShotSample;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FewShotRepository} 的 MVP 内存实现：横切B反馈层。
 *
 * <p>存储模型：{@code ConcurrentHashMap<exceptionType, 同步 List>}。
 * <ul>
 *   <li>分桶键 = exceptionType，与召回查询维度一致，O(1) 定位桶；</li>
 *   <li>桶内用 {@link java.util.Collections#synchronizedList(List) 同步 List}，
 *       兼容飞书卡片回调并发写入；</li>
 *   <li>召回时对桶做快照后排序，遵循同步 List「迭代需外部同步」契约。</li>
 * </ul>
 *
 * <p>注意：进程重启即丢，MVP 阶段够用；生产换持久化向量库 / ES，
 * 召回从「按类型最近 N 条」升级为「类型预筛 + 栈向量相似 Top-K」。
 */
@Component
public class InMemoryFewShotRepository implements FewShotRepository {

    /** limit 入参非法（<=0）时的回退上限，与 prompt few-shot 注入条数对齐。 */
    private static final int DEFAULT_LIMIT_FALLBACK = 3;

    /** exceptionType 为 null 时的分桶键，避免 NPE 且保证可召回。 */
    private static final String UNKNOWN_BUCKET_KEY = "UNKNOWN";

    private final Map<String, List<FewShotSample>> storeByExceptionType = new ConcurrentHashMap<>();

    @Override
    public void save(FewShotSample sample) {
        if (sample == null) {
            return;
        }
        String key = normalizeKey(sample.exceptionType());
        List<FewShotSample> bucket = storeByExceptionType.computeIfAbsent(
            key, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        bucket.add(sample);
    }

    @Override
    public List<FewShotSample> findByExceptionType(String exceptionType, int limit) {
        if (exceptionType == null) {
            return List.of();
        }
        List<FewShotSample> bucket = storeByExceptionType.get(exceptionType);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<FewShotSample> snapshot;
        synchronized (bucket) {
            snapshot = new ArrayList<>(bucket);
        }
        int safeLimit = limit > 0 ? limit : DEFAULT_LIMIT_FALLBACK;
        return snapshot.stream()
            .sorted(Comparator.comparing(FewShotSample::createdAt).reversed())
            .limit(safeLimit)
            .toList();
    }

    private String normalizeKey(String exceptionType) {
        return exceptionType == null ? UNKNOWN_BUCKET_KEY : exceptionType;
    }
}

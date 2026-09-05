package com.stackwatch.analyzer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stackwatch.config.CacheProperties;
import com.stackwatch.history.ErrorGroup;
import com.stackwatch.history.ErrorGroupKey;
import com.stackwatch.domain.RootCauseAnalysis;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * L1 指纹缓存：精确命中即复用历史归因，0 token。
 * 对应 B1 设计：③分析层 L1。
 *
 * MVP：Caffeine 内存实现。生产可换 Redis（跨实例共享 + 持久化）。
 */
@Component
public class FingerprintCache {

    private final Cache<ErrorGroupKey, ErrorGroup> groupCache;
    /**
     * Kept only for source compatibility with callers compiled against the pre-history API.
     * ErrorAnalyzer uses the typed cache in the normal Spring wiring path.
     */
    private final Cache<String, RootCauseAnalysis> legacyCache;

    public FingerprintCache(CacheProperties properties) {
        this.groupCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.fingerprintTtlSeconds()))
            .maximumSize(properties.fingerprintMaxSize())
            .build();
        this.legacyCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.fingerprintTtlSeconds()))
            .maximumSize(properties.fingerprintMaxSize())
            .build();
    }

    /** Looks up a complete durable grouping target by its composite exact identity. */
    public Optional<ErrorGroup> lookup(ErrorGroupKey key) {
        return Optional.ofNullable(groupCache.getIfPresent(key));
    }

    /** Warms the accelerator with the latest occurrence state returned by the repository. */
    public void put(ErrorGroupKey key, ErrorGroup group) {
        groupCache.put(key, group);
    }

    /** @deprecated use {@link #lookup(ErrorGroupKey)}. */
    @Deprecated
    public Optional<RootCauseAnalysis> lookup(String fingerprintHash) {
        return Optional.ofNullable(legacyCache.getIfPresent(fingerprintHash));
    }

    /** @deprecated use {@link #put(ErrorGroupKey, ErrorGroup)}. */
    @Deprecated
    public void put(String fingerprintHash, RootCauseAnalysis analysis) {
        legacyCache.put(fingerprintHash, analysis);
    }
}

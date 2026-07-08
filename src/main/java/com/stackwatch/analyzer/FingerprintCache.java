package com.stackwatch.analyzer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stackwatch.config.CacheProperties;
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

    private final Cache<String, RootCauseAnalysis> cache;

    public FingerprintCache(CacheProperties properties) {
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.fingerprintTtlSeconds()))
            .maximumSize(properties.fingerprintMaxSize())
            .build();
    }

    public Optional<RootCauseAnalysis> lookup(String fingerprintHash) {
        return Optional.ofNullable(cache.getIfPresent(fingerprintHash));
    }

    public void put(String fingerprintHash, RootCauseAnalysis analysis) {
        cache.put(fingerprintHash, analysis);
    }
}

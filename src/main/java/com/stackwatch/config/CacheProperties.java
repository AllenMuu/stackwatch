package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置：stackwatch.cache.*
 */
@ConfigurationProperties(prefix = "stackwatch.cache")
public record CacheProperties(
    int fingerprintTtlSeconds,
    int fingerprintMaxSize
) {
    public CacheProperties {
        if (fingerprintTtlSeconds <= 0) {
            fingerprintTtlSeconds = 86400;
        }
        if (fingerprintMaxSize <= 0) {
            fingerprintMaxSize = 100000;
        }
    }
}

package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Independent, opt-in configuration for durable error identity history. */
@ConfigurationProperties(prefix = "stackwatch.error-history")
public record ErrorHistoryProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("jdbc:postgresql://localhost:5432/stackwatch") String url,
    @DefaultValue("stackwatch") String username,
    @DefaultValue("") String password) {
}

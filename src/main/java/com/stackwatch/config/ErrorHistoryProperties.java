package com.stackwatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Independent, opt-in configuration for durable error identity history. */
@ConfigurationProperties(prefix = "stackwatch.error-history")
public record ErrorHistoryProperties(
    @DefaultValue("false") boolean enabled,
    Datasource datasource) {
    public ErrorHistoryProperties {
        datasource = datasource == null ? new Datasource(null, null, null) : datasource;
    }

    public record Datasource(
        @DefaultValue("jdbc:postgresql://localhost:5432/stackwatch") String url,
        @DefaultValue("stackwatch") String username,
        @DefaultValue("") String password) {
        public Datasource {
            url = url == null || url.isBlank() ? "jdbc:postgresql://localhost:5432/stackwatch" : url;
            username = username == null || username.isBlank() ? "stackwatch" : username;
            password = password == null ? "" : password;
        }
    }
}

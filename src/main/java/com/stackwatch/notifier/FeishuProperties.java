package com.stackwatch.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书投递配置：stackwatch.feishu.*
 * 对应 B1 设计：⑤投递层（实时告警 webhook + OpenAPI 富文本卡片）。
 *
 * webhookUrl / apiUrl 走环境变量占位（${FEISHU_WEBHOOK_URL:}），禁止硬编码
 * （遵循全局安全规约：API Key / token 永不入库）。
 *
 * apiUrl 为飞书 OpenAPI 基址，富文本卡片推送解锁后使用；MVP 默认官方基址。
 */
@ConfigurationProperties(prefix = "stackwatch.feishu")
public record FeishuProperties(
    String webhookUrl,
    String apiUrl
) {
    public FeishuProperties {
        if (webhookUrl == null) {
            webhookUrl = "";
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://open.feishu.cn/open-apis";
        }
    }
}

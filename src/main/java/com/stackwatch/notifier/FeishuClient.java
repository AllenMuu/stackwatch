package com.stackwatch.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 飞书推送客户端：⑤投递层。
 *
 * MVP 直发自定义机器人 webhook（msg_type=text）：
 *   - {@link #sendWebhook(String)} 实时告警 / 周报推送统一入口。
 *
 * 错误处理：网络失败 / 非 2xx 仅记 warn 不抛异常
 * （推送失败不得影响主分析流程，遵循「错误显式处理 + 不静默吞」：
 *  此处「显式」即日志 warn，主流程显式跳过推送）。
 *
 * 生产演进：富文本卡片用 OpenAPI（apiUrl）+ tenant_access_token，
 * 当前留 webhook 通路即可。
 */
@Component
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    /** 飞书自定义机器人 text 消息 msg_type。 */
    private static final String MSG_TYPE_TEXT = "text";

    private final FeishuProperties properties;
    private final RestTemplate restTemplate;

    public FeishuClient(FeishuProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    /**
     * 发送实时告警 / 周报文本到飞书 webhook。
     * 推送失败不抛异常，仅记 warn。
     */
    public void sendWebhook(String message) {
        String url = properties.webhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Feishu webhook url not configured, skip push");
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = Map.of(
            "msg_type", MSG_TYPE_TEXT,
            "content", Map.of("text", message)
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, request, String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("Feishu webhook pushed: status={}", resp.getStatusCode());
            } else {
                log.warn("Feishu webhook non-2xx: status={} body={}", resp.getStatusCode(), resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Feishu webhook push failed (ignored, main flow continues): {}", e.getMessage());
        }
    }
}

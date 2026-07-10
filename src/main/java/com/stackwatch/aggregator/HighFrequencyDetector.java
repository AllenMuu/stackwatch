package com.stackwatch.aggregator;

import com.stackwatch.notifier.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 高频激增检测：④聚合层。
 *
 * 单簇短窗口计数超阈值 -> 触发 {@link FeishuClient#sendWebhook} 实时告警。
 *
 * MVP 简化版：调用方传入 clusterId + 当前窗口（5 分钟）计数，
 * 超阈值即告警。阈值用命名常量，无魔法数字。
 *
 * 生产演进：滑动窗口计数（Redis ZSET）+ 同比/环比基线，
 * 当前仅做绝对阈值判断。
 */
@Service
public class HighFrequencyDetector {

    private static final Logger log = LoggerFactory.getLogger(HighFrequencyDetector.class);

    /** 5 分钟窗口激增阈值（次）。 */
    private static final int SURGE_THRESHOLD = 20;

    /** 告警窗口描述（仅用于消息文案）。 */
    private static final String WINDOW_DESC = "5 分钟";

    private final FeishuClient feishuClient;

    public HighFrequencyDetector(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    /**
     * 检测单簇激增；超阈值则推送飞书实时告警。
     *
     * @param clusterId     簇 ID
     * @param currentCount  当前窗口（5 分钟）内该簇累计次数
     * @return true 表示已触发告警
     */
    public boolean checkSurge(String clusterId, int currentCount) {
        if (currentCount < SURGE_THRESHOLD) {
            return false;
        }
        log.warn("Surge detected: cluster={} count={} threshold={}", clusterId, currentCount, SURGE_THRESHOLD);
        String message = String.format(
            "[StackWatch 激增告警] 簇 %s 在 %s内累计 %d 次（阈值 %d），请及时排查。",
            clusterId, WINDOW_DESC, currentCount, SURGE_THRESHOLD);
        feishuClient.sendWebhook(message);
        return true;
    }
}

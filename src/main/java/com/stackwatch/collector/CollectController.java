package com.stackwatch.collector;

import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.web.AnalyzeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集层 HTTP 上报入口：POST /collect。
 *
 * 与 {@code /analyze} 的分工：
 * - {@code /analyze}（{@code AnalysisController}）直接调 ErrorAnalyzer，
 *   调用方自行组装好字段，eventId/occurredAt 也由调用方负责。
 * - {@code /collect} 走采集层 {@link ErrorEventCollector}，由采集层补齐
 *   eventId（UUID）/ occurredAt（Instant.now），是"对外上报异常"的标准入口。
 *
 * 复用 {@link AnalyzeRequest} 作为请求体 DTO（字段语义一致：appName/env/
 * exceptionType/exceptionMessage/stackTrace/mdc）。
 *
 * 对应 B1 设计：①采集层 HTTP 上报通道。
 */
@RestController
@RequestMapping("/collect")
public class CollectController {

    private final ErrorEventCollector collector;

    public CollectController(ErrorEventCollector collector) {
        this.collector = collector;
    }

    @PostMapping
    public AnalysisResult collect(@RequestBody AnalyzeRequest request) {
        // 走字段版 collect 重载：DTO 已携带拆解后的异常字段，无需重建 Throwable
        return collector.collect(
            request.appName(),
            request.env(),
            request.exceptionType(),
            request.exceptionMessage(),
            request.stackTrace(),
            request.mdc()
        );
    }
}

package com.stackwatch.analyzer;

import com.stackwatch.domain.AnalysisResult;
import com.stackwatch.domain.ErrorEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ErrorAnalyzer 集成测试：需真实 LLM API Key。
 * 未配置 DASHSCOPE_API_KEY 时自动跳过（CI 友好）。
 *
 * 解锁方式：export DASHSCOPE_API_KEY=sk-... 后运行该测试。
 *
 * TODO：补充 mock ChatClient 的单元测试，覆盖 L1 缓存命中、置信度兜底等逻辑，
 *       使核心归并逻辑不依赖 LLM 即可测试（达成 80% 覆盖率目标）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = "sk-.+")
class ErrorAnalyzerTest {

    @Autowired
    ErrorAnalyzer errorAnalyzer;

    @Test
    void shouldAnalyzeNpeAndReturnRootCause() {
        ErrorEvent event = new ErrorEvent(
            "test-1", "order-service", "prod", Instant.now(),
            "NullPointerException",
            "Cannot invoke \"String.length()\" because \"order\" is null",
            List.of(
                "at com.foo.OrderService.process(OrderService.java:42)",
                "at com.foo.OrderController.handle(OrderController.java:17)"
            ),
            Map.of("traceId", "trace-123")
        );

        AnalysisResult result = errorAnalyzer.analyze(event);

        assertNotNull(result);
        assertNotNull(result.analysis());
        assertNotNull(result.analysis().rootCause());
        // 输出便于人工观察 LLM 归因质量
        System.out.println("=== 根因分析结果 ===");
        System.out.println("路径: " + result.path());
        System.out.println("根因: " + result.analysis().rootCause());
        System.out.println("分类: " + result.analysis().category());
        System.out.println("置信度: " + result.analysis().confidence());
        System.out.println("转人工: " + result.analysis().needHumanReview());
    }
}

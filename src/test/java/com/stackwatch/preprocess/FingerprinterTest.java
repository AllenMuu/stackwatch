package com.stackwatch.preprocess;

import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fingerprinter 单元测试（无 LLM 依赖，CI 可跑）。
 * 覆盖：确定性、业务帧区分、行号/框架帧忽略、兜底回退。
 */
class FingerprinterTest {

    private final Fingerprinter fingerprinter = new Fingerprinter(5);

    @Test
    void shouldGenerateDeterministicFingerprintForSameStack() {
        ErrorEvent event1 = npeEvent("com.foo.OrderService.process", "com.foo.OrderController.handle");
        ErrorEvent event2 = npeEvent("com.foo.OrderService.process", "com.foo.OrderController.handle");

        ErrorFingerprint fp1 = fingerprinter.generate(event1);
        ErrorFingerprint fp2 = fingerprinter.generate(event2);

        assertEquals(fp1.hash(), fp2.hash(), "相同堆栈应生成相同指纹");
        assertNotNull(fp1.hash());
        assertEquals(64, fp1.hash().length(), "SHA-256 hex 应为 64 字符");
        assertTrue(fp1.hash().matches("[0-9a-f]{64}"), "应为 hex 字符串");
    }

    @Test
    void shouldDifferWhenBusinessFrameDiffers() {
        ErrorEvent event1 = npeEvent("com.foo.OrderService.process", "com.foo.OrderController.handle");
        ErrorEvent event2 = npeEvent("com.foo.PaymentService.pay", "com.foo.PayController.handle");

        assertNotEquals(
            fingerprinter.generate(event1).hash(),
            fingerprinter.generate(event2).hash(),
            "不同业务帧应生成不同指纹"
        );
    }

    @Test
    void shouldIgnoreLineNumbersAndFrameworkFrames() {
        // 同一调用路径，行号不同 + 框架帧不同 -> 业务帧足够时指纹应一致
        ErrorEvent withLineNumbers = new ErrorEvent(
            "id1", "app", "prod", Instant.now(), "NullPointerException", "msg",
            List.of(
                "at com.foo.OrderService.process(OrderService.java:42)",
                "at com.foo.OrderController.handle(OrderController.java:17)",
                "at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1067)",
                "at javax.servlet.http.HttpServlet.service(HttpServlet.java:623)"
            ),
            Map.of()
        );
        ErrorEvent differentLines = new ErrorEvent(
            "id2", "app", "prod", Instant.now(), "NullPointerException", "msg",
            List.of(
                "at com.foo.OrderService.process(OrderService.java:99)",
                "at com.foo.OrderController.handle(OrderController.java:55)",
                "at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:117)"
            ),
            Map.of()
        );

        assertEquals(
            fingerprinter.generate(withLineNumbers).hash(),
            fingerprinter.generate(differentLines).hash(),
            "行号变化 + 框架帧变化不应影响指纹（业务帧稳定）"
        );
    }

    @Test
    void shouldFallbackToTopFramesWhenAppFramesInsufficient() {
        // 仅含框架帧时，应用帧不足 -> 回退到栈顶 N 帧，指纹仍可生成
        ErrorEvent event = new ErrorEvent(
            "id", "app", "prod", Instant.now(), "NullPointerException", "msg",
            List.of(
                "at java.lang.String.substring(String.java:2000)",
                "at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"
            ),
            Map.of()
        );
        ErrorFingerprint fp = fingerprinter.generate(event);

        assertNotNull(fp.hash());
        assertFalse(fp.topFrames().isEmpty(), "应用帧不足时应回退到栈顶帧");
    }

    @Test
    void shouldIncludeExceptionTypeInFingerprint() {
        // 同一栈帧，不同异常类型 -> 指纹应不同
        ErrorEvent npe = new ErrorEvent("id1", "app", "prod", Instant.now(),
            "NullPointerException", "msg",
            Arrays.asList("at com.foo.OrderService.process(OrderService.java:1)"), Map.of());
        ErrorEvent illegalArg = new ErrorEvent("id2", "app", "prod", Instant.now(),
            "IllegalArgumentException", "msg",
            Arrays.asList("at com.foo.OrderService.process(OrderService.java:1)"), Map.of());

        assertNotEquals(
            fingerprinter.generate(npe).hash(),
            fingerprinter.generate(illegalArg).hash(),
            "不同异常类型应生成不同指纹"
        );
    }

    private ErrorEvent npeEvent(String... frames) {
        List<String> stack = Arrays.stream(frames)
            .map(f -> "at " + f + "(Fake.java:1)")
            .toList();
        return new ErrorEvent(
            "id", "order-service", "prod", Instant.now(),
            "NullPointerException", "Cannot invoke method on null",
            stack, Map.of()
        );
    }
}

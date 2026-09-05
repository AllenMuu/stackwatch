package com.stackwatch.preprocess;

import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import com.stackwatch.domain.ThrowableInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fingerprinter 单元测试（无 LLM 依赖，CI 可跑）。
 * 覆盖：确定性、业务帧区分、行号/框架帧忽略、兜底回退。
 */
class FingerprinterTest {

    private final Fingerprinter fingerprinter = new Fingerprinter(5);

    @Test
    void shouldPreserveV1CanonicalFingerprint() {
        ErrorFingerprint fingerprint = fingerprinter.generate(
            npeEvent("com.foo.OrderService.process", "com.foo.OrderController.handle"));

        assertEquals(
            "18ced847651b1a84647e35684c3bc76bb69628283d13f6f70e96b2f573731639",
            fingerprint.hash());
        assertEquals(com.stackwatch.domain.FingerprintVersion.V1, fingerprint.version());
        assertNull(fingerprint.looseHash());
    }

    @Test
    void shouldKeepSemanticMessageVariantsStrictButRelatedLoosely() {
        NormalizedError unauthorized = normalized("HTTP 401 from upstream");
        NormalizedError serverError = normalized("HTTP 500 from upstream");

        ErrorFingerprint strict401 = fingerprinter.generateV2(unauthorized, "orders");
        ErrorFingerprint strict500 = fingerprinter.generateV2(serverError, "orders");

        assertNotEquals(strict401.hash(), strict500.hash());
        assertEquals(strict401.looseHash(), strict500.looseHash());
    }

    @Test
    void shouldScopeStrictAndLooseV2IdentityToApplication() {
        NormalizedError normalized = normalized("HTTP 500 from upstream");

        ErrorFingerprint orders = fingerprinter.generateV2(normalized, "orders");
        ErrorFingerprint billing = fingerprinter.generateV2(normalized, "billing");

        assertNotEquals(orders.hash(), billing.hash());
        assertNotEquals(orders.looseHash(), billing.looseHash());
    }

    @Test
    void shouldPinV2CanonicalRenderingAndExplainItsParts() {
        ErrorFingerprint fingerprint = fingerprinter.generateV2(
            normalized("HTTP 500 from upstream"), "orders");

        assertEquals(com.stackwatch.domain.FingerprintVersion.V2, fingerprint.version());
        assertEquals(
            "665dfc20077c445dc74aeb7022572a63544d8abd0711c08e724434c62e8653c6",
            fingerprint.hash());
        assertEquals(
            "2d219bccac2af1ed3ec3b8c0c11d01c786e2e840764c0ade3d68f2aed79b895b",
            fingerprint.looseHash());
        assertEquals(
            List.of(
                new com.stackwatch.domain.FingerprintRecordPart(
                    com.stackwatch.domain.FingerprintRecordPart.PartType.CUSTOM,
                    List.of("app=orders")),
                com.stackwatch.domain.FingerprintRecordPart.exception("java.sql.SQLException"),
                new com.stackwatch.domain.FingerprintRecordPart(
                    com.stackwatch.domain.FingerprintRecordPart.PartType.CUSTOM,
                    List.of("message=HTTP 500 from upstream")),
                com.stackwatch.domain.FingerprintRecordPart.frame(
                    List.of("com.example.OrderRepository#load"))),
            fingerprint.record());
    }

    @Test
    void shouldMakeEquivalentWrapperTypesShareV2Identity() {
        ErrorNormalizer normalizer = new ErrorNormalizer(
            new com.stackwatch.config.FingerprintProperties(
                List.of("com.example"), List.of(), List.of()),
            5);
        ThrowableInfo root = new ThrowableInfo(
            "java.sql.SQLException",
            "Order 981273 not found",
            List.of("at com.example.OrderRepository.load(OrderRepository.java:42)"),
            null);
        ErrorEvent completion = eventWithThrowable(
            "orders",
            new ThrowableInfo(
                "java.util.concurrent.CompletionException", "failed", List.of(), root));
        ErrorEvent execution = eventWithThrowable(
            "orders",
            new ThrowableInfo("java.util.concurrent.ExecutionException", "failed", List.of(), root));

        assertEquals(
            fingerprinter.generateV2(normalizer.normalize(completion), "orders").hash(),
            fingerprinter.generateV2(normalizer.normalize(execution), "orders").hash());
    }

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
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id1", "app", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo(
                "NullPointerException", "msg",
                List.of(
                    "at com.foo.OrderService.process(OrderService.java:42)",
                    "at com.foo.OrderController.handle(OrderController.java:17)",
                    "at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1067)",
                    "at javax.servlet.http.HttpServlet.service(HttpServlet.java:623)"),
                null)
        );
        ErrorEvent differentLines = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id2", "app", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo(
                "NullPointerException", "msg",
                List.of(
                    "at com.foo.OrderService.process(OrderService.java:99)",
                    "at com.foo.OrderController.handle(OrderController.java:55)",
                    "at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:117)"),
                null)
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
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id", "app", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo(
                "NullPointerException", "msg",
                List.of(
                    "at java.lang.String.substring(String.java:2000)",
                    "at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)"),
                null)
        );
        ErrorFingerprint fp = fingerprinter.generate(event);

        assertNotNull(fp.hash());
        assertFalse(fp.topFrames().isEmpty(), "应用帧不足时应回退到栈顶帧");
    }

    @Test
    void shouldIncludeExceptionTypeInFingerprint() {
        // 同一栈帧，不同异常类型 -> 指纹应不同
        ErrorEvent npe = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id1", "app", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo(
                "NullPointerException", "msg",
                Arrays.asList("at com.foo.OrderService.process(OrderService.java:1)"), null));
        ErrorEvent illegalArg = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id2", "app", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo(
                "IllegalArgumentException", "msg",
                Arrays.asList("at com.foo.OrderService.process(OrderService.java:1)"), null));

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
            new ErrorEvent.Context(
                new ErrorEvent.Identity("id", "order-service", "prod"), Instant.now(), Map.of()),
            new ThrowableInfo("NullPointerException", "Cannot invoke method on null", stack, null)
        );
    }

    private static NormalizedError normalized(String message) {
        return new NormalizedError(
            new NormalizedError.Types(
                "java.util.concurrent.CompletionException", "java.sql.SQLException"),
            new NormalizedError.Messages("failed", message),
            new NormalizedError.Frames(
                List.of("com.example.OrderRepository#load"),
                List.of("com.example.OrderRepository#load")),
            1);
    }

    private static ErrorEvent eventWithThrowable(String appName, ThrowableInfo throwable) {
        return new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("event", appName, "prod"), Instant.EPOCH, Map.of()),
            throwable);
    }
}

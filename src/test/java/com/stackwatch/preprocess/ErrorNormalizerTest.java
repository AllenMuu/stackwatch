package com.stackwatch.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.stackwatch.config.FingerprintProperties;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ThrowableInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorNormalizerTest {

    @Test
    void resolvesDeepestTypedNonWrapperCause() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ErrorEvent wrapped = event(
            "orders",
            throwable(
                "java.util.concurrent.CompletionException",
                "completion failed",
                List.of("at java.util.concurrent.CompletableFuture.join(CompletableFuture.java:1)"),
                throwable(
                    "java.util.concurrent.ExecutionException",
                    "execution failed",
                    List.of("at java.util.concurrent.FutureTask.get(FutureTask.java:1)"),
                    throwable(
                        "java.sql.SQLException",
                        "Order 981273 not found",
                        List.of("at com.example.OrderRepository.load(OrderRepository.java:42)"),
                        null))));

        NormalizedError normalized = normalizer.normalize(wrapped);

        assertEquals("java.util.concurrent.CompletionException", normalized.outerExceptionType());
        assertEquals("java.sql.SQLException", normalized.effectiveExceptionType());
        assertEquals("Order <NUM> not found", normalized.normalizedRootCauseMessage());
        assertEquals(List.of("com.example.OrderRepository#load"), normalized.applicationFrames());
        assertEquals(2, normalized.causeDepth());
    }

    @Test
    void effectiveCauseApplicationFramesTakePriorityOverOuterFrames() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ErrorEvent event = event(
            "orders",
            throwable(
                "java.util.concurrent.CompletionException",
                "failed",
                List.of("at com.example.OrderFacade.submit(OrderFacade.java:11)"),
                throwable(
                    "java.sql.SQLException",
                    "failed",
                    List.of("at com.example.OrderRepository.load(OrderRepository.java:99)"),
                    null)));

        NormalizedError normalized = normalizer.normalize(event);

        assertEquals(List.of("com.example.OrderRepository#load"), normalized.applicationFrames());
    }

    @Test
    void outerApplicationFramesTakePriorityWhenRootCauseHasNoApplicationFrames() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ErrorEvent event = event(
            "orders",
            throwable(
                "java.util.concurrent.CompletionException",
                "failed",
                List.of("at com.example.OrderFacade.submit(OrderFacade.java:11)"),
                throwable(
                    "java.net.SocketTimeoutException",
                    "timed out",
                    List.of("at java.net.SocketInputStream.read(SocketInputStream.java:99)"),
                    null)));

        NormalizedError normalized = normalizer.normalize(event);

        assertEquals(List.of("com.example.OrderFacade#submit"), normalized.applicationFrames());
        assertEquals(
            List.of("java.net.SocketInputStream#read"), normalized.rootCauseFrames());
    }

    @Test
    void effectiveRawFramesTakePriorityOverOuterRawFramesAsFinalFallback() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ErrorEvent event = event(
            "orders",
            throwable(
                "java.util.concurrent.CompletionException",
                "failed",
                List.of("at org.vendor.Outer.invoke(Outer.java:11)"),
                throwable(
                    "java.net.SocketTimeoutException",
                    "timed out",
                    List.of("at org.vendor.Root.invoke(Root.java:99)"),
                    null)));

        assertEquals(
            List.of("org.vendor.Root#invoke"),
            normalizer.normalize(event).applicationFrames());
    }

    @Test
    void configuredApplicationPackagesAreAnExclusiveAllowlist() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ErrorEvent event = event(
            "orders",
            throwable(
                "java.lang.IllegalStateException",
                "failed",
                List.of(
                    "at com.vendor.Library.invoke(Library.java:5)",
                    "at com.example.OrderService.submit(OrderService.java:42)",
                    "at com.examplex.NotOurCode.run(NotOurCode.java:9)"),
                null));

        assertEquals(
            List.of("com.example.OrderService#submit"),
            normalizer.normalize(event).applicationFrames());
    }

    @Test
    void emptyAllowlistUsesFrameworkDenylistAndIgnoresLineNumbers() {
        ErrorNormalizer normalizer = normalizer(List.of());
        ErrorEvent first = event(
            "orders",
            throwable(
                "java.lang.IllegalStateException",
                "failed",
                List.of(
                    "at org.springframework.web.Dispatcher.route(Dispatcher.java:1)",
                    "at com.vendor.OrderService.submit(OrderService.java:42)"),
                null));
        ErrorEvent second = event(
            "orders",
            throwable(
                "java.lang.IllegalStateException",
                "failed",
                List.of(
                    "at org.springframework.web.Dispatcher.route(Dispatcher.java:900)",
                    "at com.vendor.OrderService.submit(OrderService.java:77)"),
                null));

        assertEquals(
            List.of("com.vendor.OrderService#submit"),
            normalizer.normalize(first).applicationFrames());
        assertEquals(
            normalizer.normalize(first).applicationFrames(),
            normalizer.normalize(second).applicationFrames());
    }

    @Test
    void messageNormalizerMasksVolatileIdentifiersAndSelectedQueryValues() {
        MessageNormalizer messageNormalizer = messageNormalizer(List.of());

        assertEquals("Order <NUM> not found", messageNormalizer.normalize("Order 981273 not found"));
        assertEquals(
            "request <UUID> from <IP> at <DATETIME> hash <HASH> url "
                + "https://api.example/orders?token=<QUERY>&page=2",
            messageNormalizer.normalize(
                "request 550e8400-e29b-41d4-a716-446655440000 from 10.20.30.40 at "
                    + "2026-09-05T12:34:56Z hash abcdef0123456789abcdef0123456789 url "
                    + "https://api.example/orders?token=very-secret&page=2"));
        assertEquals("retry at <TIME>", messageNormalizer.normalize("retry at 09:31:22.456"));
    }

    @Test
    void messageNormalizerPreservesSemanticStatusAndConfiguredApplicationCodes() {
        MessageNormalizer messageNormalizer = messageNormalizer(List.of("APP-981273"));

        String unauthorized = messageNormalizer.normalize(
            "HTTP 401 SQLState 23505 errno 1062 gRPC UNAVAILABLE code APP-981273");
        String serverError = messageNormalizer.normalize(
            "HTTP 500 SQLState 23505 errno 1062 gRPC UNAVAILABLE code APP-981273");

        assertEquals(
            "HTTP 401 SQLState 23505 errno 1062 gRPC UNAVAILABLE code APP-981273",
            unauthorized);
        assertEquals(
            "HTTP 500 SQLState 23505 errno 1062 gRPC UNAVAILABLE code APP-981273",
            serverError);
        assertNotEquals(unauthorized, serverError);
    }

    @Test
    void normalizationIgnoresEventIdentityAndOccurrenceTime() {
        ErrorNormalizer normalizer = normalizer(List.of("com.example"));
        ThrowableInfo throwable = throwable(
            "java.lang.IllegalArgumentException",
            "Order 981273 not found",
            List.of("at com.example.OrderService.load(OrderService.java:42)"),
            null);
        ErrorEvent first = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("event-1", "orders", "prod"),
                Instant.parse("2026-09-05T00:00:00Z"),
                Map.of("source", "http")),
            throwable);
        ErrorEvent second = new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("event-2", "orders", "prod"),
                Instant.parse("2026-09-06T00:00:00Z"),
                Map.of("source", "kafka")),
            throwable);

        assertEquals(normalizer.normalize(first), normalizer.normalize(second));
    }

    @Test
    void resolverStopsAtRepeatedThrowableIdentity() {
        FingerprintProperties properties = new FingerprintProperties(List.of(), List.of(), List.of());
        CauseResolver resolver = new CauseResolver(properties);
        ThrowableInfo repeated = mock(ThrowableInfo.class);
        when(repeated.type()).thenReturn("java.lang.IllegalStateException");
        when(repeated.message()).thenReturn("prefix");
        when(repeated.stackTrace()).thenReturn(List.of());
        when(repeated.cause()).thenReturn(repeated);

        CauseResolver.ResolvedCause result = resolver.resolve(repeated);

        assertEquals(repeated, result.effective());
        assertEquals(0, result.depth());
    }

    @Test
    void resolverStopsAtExactlyThirtyTwoNodes() {
        FingerprintProperties properties = new FingerprintProperties(List.of(), List.of(), List.of());
        CauseResolver resolver = new CauseResolver(properties);
        ThrowableInfo chain = null;
        for (int index = 32; index >= 0; index--) {
            chain = new ThrowableInfo("type-" + index, "message", List.of(), chain);
        }

        CauseResolver.ResolvedCause result = resolver.resolve(chain);

        assertEquals("type-31", result.effective().type());
        assertEquals(31, result.depth());
    }

    private static ErrorNormalizer normalizer(List<String> applicationPackages) {
        FingerprintProperties properties = new FingerprintProperties(
            applicationPackages, List.of(), List.of());
        return new ErrorNormalizer(
            new CauseResolver(properties),
            new MessageNormalizer(properties),
            new StackFrameNormalizer(properties, 5));
    }

    private static MessageNormalizer messageNormalizer(List<String> applicationErrorCodes) {
        return new MessageNormalizer(
            new FingerprintProperties(List.of(), List.of(), applicationErrorCodes));
    }

    private static ErrorEvent event(String appName, ThrowableInfo throwable) {
        return new ErrorEvent(
            new ErrorEvent.Context(
                new ErrorEvent.Identity("event", appName, "prod"), Instant.EPOCH, Map.of()),
            throwable);
    }

    private static ThrowableInfo throwable(
        String type, String message, List<String> stackTrace, ThrowableInfo cause) {
        return new ThrowableInfo(type, message, stackTrace, cause);
    }
}

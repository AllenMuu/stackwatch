package com.stackwatch.collector;

import com.stackwatch.domain.ThrowableInfo;
import com.stackwatch.web.AnalyzeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollectControllerTest {

    @Test
    void convertsLegacyFieldsToOneNodeThrowableInfo() throws MethodArgumentNotValidException {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        CollectController controller = new CollectController(collector);
        AnalyzeRequest request = new AnalyzeRequest(
            "orders", "prod", "java.lang.IllegalStateException", "bad state",
            List.of("at com.example.OrderService.place(OrderService.java:42)"), Map.of());

        controller.collect(request);

        verify(collector).collect(
            eq("orders"), eq("prod"),
            eq(new ThrowableInfo(
                "java.lang.IllegalStateException", "bad state",
                List.of("at com.example.OrderService.place(OrderService.java:42)"), null)),
            eq(Map.of()));
    }

    @Test
    void preservesStructuredThrowableCauseChain() throws MethodArgumentNotValidException {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        CollectController controller = new CollectController(collector);
        ThrowableInfo exception = new ThrowableInfo(
            "java.lang.RuntimeException", "request failed", List.of(),
            new ThrowableInfo("java.sql.SQLException", "connection refused", List.of(), null));
        AnalyzeRequest request = new AnalyzeRequest("orders", "prod", null, null, null, exception, Map.of());

        controller.collect(request);

        verify(collector).collect("orders", "prod", exception, Map.of());
    }

    @Test
    void rejectsMixedLegacyAndStructuredThrowableForms() {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        CollectController controller = new CollectController(collector);
        AnalyzeRequest mixedRequest = new AnalyzeRequest(
            "orders", "prod", "java.lang.RuntimeException", "request failed", List.of(),
            new ThrowableInfo("java.lang.RuntimeException", "request failed", List.of(), null), Map.of());

        assertThrows(MethodArgumentNotValidException.class, () -> controller.collect(mixedRequest));
    }
}

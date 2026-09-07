package com.stackwatch.collector;

import com.stackwatch.domain.ThrowableInfo;
import com.stackwatch.web.AnalyzeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollectControllerTest {

    @Test
    void convertsLegacyFieldsToOneNodeThrowableInfo() throws MethodArgumentNotValidException {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        CollectController controller = new CollectController(collector);
        AnalyzeRequest request = AnalyzeRequest.legacy(
            new AnalyzeRequest.Context("orders", "prod", Map.of()),
            new ThrowableInfo(
                "java.lang.IllegalStateException", "bad state",
                List.of("at com.example.OrderService.place(OrderService.java:42)"), null));

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
        AnalyzeRequest request = AnalyzeRequest.structured(
            new AnalyzeRequest.Context("orders", "prod", Map.of()), exception);

        controller.collect(request);

        verify(collector).collect("orders", "prod", exception, Map.of());
    }

    @Test
    void rejectsMixedLegacyAndStructuredThrowableForms() {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        CollectController controller = new CollectController(collector);
        AnalyzeRequest mixedRequest = new AnalyzeRequest(
            new AnalyzeRequest.Context("orders", "prod", Map.of()),
            new ThrowableInfo("java.lang.RuntimeException", "request failed", List.of(), null),
            new ThrowableInfo("java.lang.RuntimeException", "request failed", List.of(), null));

        assertThrows(MethodArgumentNotValidException.class, () -> controller.collect(mixedRequest));
    }

    @Test
    void deserializesStructuredThrowableCauseChainFromJson() throws Exception {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CollectController(collector)).build();

        mockMvc.perform(post("/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "appName": "orders",
                      "env": "prod",
                      "exception": {
                        "type": "java.lang.RuntimeException",
                        "message": "request failed",
                        "stackTrace": ["at com.example.OrderService.place(OrderService.java:42)"],
                        "cause": {
                          "type": "java.sql.SQLException",
                          "message": "connection refused",
                          "stackTrace": ["at org.postgresql.Driver.connect(Driver.java:1)"]
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk());

        verify(collector).collect(
            eq("orders"), eq("prod"),
            eq(new ThrowableInfo(
                "java.lang.RuntimeException", "request failed",
                List.of("at com.example.OrderService.place(OrderService.java:42)"),
                new ThrowableInfo(
                    "java.sql.SQLException", "connection refused",
                    List.of("at org.postgresql.Driver.connect(Driver.java:1)"), null))),
            eq(Map.of()));
    }

    @Test
    void rejectsMixedStructuredAndLegacyFormsAtHttpBoundary() throws Exception {
        ErrorEventCollector collector = mock(ErrorEventCollector.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CollectController(collector)).build();

        mockMvc.perform(post("/collect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "appName": "orders",
                      "exceptionType": "java.lang.RuntimeException",
                      "exception": {"type": "java.lang.RuntimeException"}
                    }
                    """))
            .andExpect(status().isBadRequest());
    }
}

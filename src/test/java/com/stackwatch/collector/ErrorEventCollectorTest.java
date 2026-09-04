package com.stackwatch.collector;

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.stackwatch.analyzer.ErrorAnalyzer;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ThrowableInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorEventCollectorTest {

    @Test
    void capturesDirectThrowableCauseChain() {
        ErrorAnalyzer analyzer = mock(ErrorAnalyzer.class);
        ErrorEventCollector collector = new ErrorEventCollector(analyzer);
        IllegalArgumentException cause = new IllegalArgumentException("bad query");
        RuntimeException outer = new RuntimeException("request failed", cause);

        collector.collect("orders", "prod", outer, Map.of("traceId", "trace-1"));

        ArgumentCaptor<ErrorEvent> eventCaptor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(analyzer).analyze(eventCaptor.capture());
        ThrowableInfo exception = eventCaptor.getValue().exception();
        assertEquals(RuntimeException.class.getName(), exception.type());
        assertEquals("request failed", exception.message());
        assertEquals(IllegalArgumentException.class.getName(), exception.cause().type());
        assertEquals("bad query", exception.cause().message());
    }

    @Test
    void stopsBeforeDuplicatingThrowableIdentityCycle() {
        ErrorAnalyzer analyzer = mock(ErrorAnalyzer.class);
        ErrorEventCollector collector = new ErrorEventCollector(analyzer);

        collector.collect("orders", "prod", new SelfCausingThrowable(), Map.of());

        ArgumentCaptor<ErrorEvent> eventCaptor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(analyzer).analyze(eventCaptor.capture());
        assertEquals(SelfCausingThrowable.class.getName(), eventCaptor.getValue().exception().type());
        assertNull(eventCaptor.getValue().exception().cause());
    }

    @Test
    void retainsAtMostThirtyTwoThrowableCauseNodes() {
        ErrorAnalyzer analyzer = mock(ErrorAnalyzer.class);
        ErrorEventCollector collector = new ErrorEventCollector(analyzer);
        Throwable chain = throwableChain(33);

        collector.collect("orders", "prod", chain, Map.of());

        ArgumentCaptor<ErrorEvent> eventCaptor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(analyzer).analyze(eventCaptor.capture());
        assertEquals(32, causeDepth(eventCaptor.getValue().exception()));
    }

    @Test
    void capturesLogbackProxyCauseChain() {
        IThrowableProxy outer = mock(IThrowableProxy.class);
        IThrowableProxy cause = mock(IThrowableProxy.class);
        when(outer.getClassName()).thenReturn("java.lang.RuntimeException");
        when(outer.getMessage()).thenReturn("request failed");
        when(outer.getCause()).thenReturn(cause);
        when(cause.getClassName()).thenReturn("java.sql.SQLException");
        when(cause.getMessage()).thenReturn("connection refused");

        ThrowableInfo exception = ErrorEventCollector.toThrowableInfo(outer);

        assertEquals("java.lang.RuntimeException", exception.type());
        assertEquals("java.sql.SQLException", exception.cause().type());
        assertEquals("connection refused", exception.cause().message());
    }

    private static Throwable throwableChain(int count) {
        Throwable current = null;
        for (int index = count; index > 0; index--) {
            current = new RuntimeException("node-" + index, current);
        }
        return current;
    }

    private static int causeDepth(ThrowableInfo exception) {
        int depth = 0;
        ThrowableInfo current = exception;
        while (current != null) {
            depth++;
            current = current.cause();
        }
        return depth;
    }

    private static final class SelfCausingThrowable extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}

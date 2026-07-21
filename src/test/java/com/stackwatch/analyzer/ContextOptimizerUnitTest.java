package com.stackwatch.analyzer;

import com.stackwatch.config.ContextOptimizerProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextOptimizer 纯单元测试：截断防线逻辑，不依赖 Spring 上下文。
 *
 * 用小阈值（exceptionMessage=5 / mdc=3 / toolResult=10）便于构造超长样本，
 * 覆盖：短值不截断、长值截断带原长度标记、null 安全、不可变、@Tool 返回值兜底。
 */
class ContextOptimizerUnitTest {

    private static final int MAX_MSG = 5;
    private static final int MAX_MDC = 3;
    private static final int MAX_TOOL = 10;

    private final ContextOptimizer optimizer = new ContextOptimizer(
        new ContextOptimizerProperties(MAX_MSG, MAX_MDC, MAX_TOOL));

    // ===== optimizePromptVars =====

    @Test
    void shortExceptionMessageNotTruncated() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exceptionMessage", "NPE");

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        assertEquals("NPE", optimized.get("exceptionMessage"));
        assertFalse(optimized.get("exceptionMessage").toString().contains("truncated"));
    }

    @Test
    void longExceptionMessageTruncatedWithOriginalLength() {
        Map<String, Object> vars = new HashMap<>();
        String longMsg = "Cannot invoke String.length because order is null";
        vars.put("exceptionMessage", longMsg);

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        String result = (String) optimized.get("exceptionMessage");
        assertTrue(result.startsWith(longMsg.substring(0, MAX_MSG)), "应保留前 maxLen 字符");
        assertTrue(result.contains("truncated"), "截断后应带标记");
        assertTrue(result.contains("original=" + longMsg.length() + " chars"), "标记应含原长度");
    }

    @Test
    void nullExceptionMessagePreserved() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exceptionMessage", null);

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        assertNull(optimized.get("exceptionMessage"), "null 入参不应被 String.valueOf 改成 \"null\"");
    }

    @Test
    void longMdcTruncated() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("mdc", "traceId=abc&userId=42");

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        String result = (String) optimized.get("mdc");
        assertTrue(result.contains("truncated"));
        assertTrue(result.contains("original="));
    }

    @Test
    void nonStringFieldsPreservedUntouched() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exceptionMessage", "ok");
        vars.put("appName", "order-service");
        vars.put("stackFrames", "frame-1\nframe-2");

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        assertEquals("order-service", optimized.get("appName"));
        assertEquals("frame-1\nframe-2", optimized.get("stackFrames"));
    }

    @Test
    void optimizePromptVarsDoesNotMutateInput() {
        Map<String, Object> vars = new HashMap<>();
        String longMsg = "this message is definitely longer than five chars";
        vars.put("exceptionMessage", longMsg);
        int originalLen = longMsg.length();

        optimizer.optimizePromptVars(vars);

        assertEquals(originalLen, longMsg.length(), "入参 Map 与原字符串不应被修改");
        assertEquals(longMsg, vars.get("exceptionMessage"));
    }

    // ===== truncateToolResult =====

    @Test
    void shortToolResultNotTruncated() {
        String result = optimizer.truncateToolResult("short", "queryRecentChanges");

        assertEquals("short", result);
    }

    @Test
    void longToolResultTruncatedWithMarker() {
        String raw = "0123456789A"; // 11 chars > MAX_TOOL(10)

        String result = optimizer.truncateToolResult(raw, "queryTraceContext");

        assertTrue(result.startsWith("0123456789"), "应保留前 maxLen 字符");
        assertTrue(result.contains("truncated"));
        assertTrue(result.contains("original=11 chars"), "标记应含原长度 11");
    }

    @Test
    void toolResultAtExactLimitNotTruncated() {
        String raw = "0123456789"; // 恰好 10 chars == MAX_TOOL

        String result = optimizer.truncateToolResult(raw, "querySimilarHistory");

        assertEquals("0123456789", result, "长度等于上限不应截断");
        assertFalse(result.contains("truncated"));
    }

    @Test
    void nullToolResultReturnsNull() {
        String result = optimizer.truncateToolResult(null, "queryTraceContext");

        assertNull(result);
    }

    @Test
    void truncatedResultLengthEqualsMaxLenPlusSuffix() {
        String raw = "0123456789ABCDEFGH"; // 18 chars

        String result = optimizer.truncateToolResult(raw, "queryTraceContext");

        // 截断体 = 前 MAX_TOOL 字符；后缀 = "...[truncated, original=18 chars]"
        int expectedBodyLen = MAX_TOOL;
        String expectedSuffix = "...[truncated, original=18 chars]";
        assertEquals(expectedBodyLen + expectedSuffix.length(), result.length());
    }
}

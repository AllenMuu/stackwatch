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
 * ContextOptimizer 纯单元测试：DoS 兜底 + head+tail 截断逻辑，不依赖 Spring 上下文。
 *
 * <p>用小阈值（maxFieldLength=20）构造超长样本，覆盖：
 * 短值不截断、长值 head+tail 保首尾、tail 根因细节存活、mdc 整行不破、
 * 原长度标记、null 安全、不可变、@Tool 返回值兜底、恰好等于上限不截断。
 */
class ContextOptimizerUnitTest {

    private static final int MAX_FIELD = 20;

    private final ContextOptimizer optimizer = new ContextOptimizer(
        new ContextOptimizerProperties(MAX_FIELD));

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
    void longExceptionMessageKeepsHeadAndTail() {
        // 头部 HEAD + 中间填充 + 尾部 ROOT_CAUSE（根因细节在尾部，旧的头截断会丢）
        String longMsg = "HEAD" + "x".repeat(30) + "ROOT_CAUSE";
        Map<String, Object> vars = new HashMap<>();
        vars.put("exceptionMessage", longMsg);

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        String result = (String) optimized.get("exceptionMessage");
        assertTrue(result.contains("HEAD"), "head 应保留首部 HEAD");
        assertTrue(result.contains("ROOT_CAUSE"), "tail 应保留尾部根因 ROOT_CAUSE（旧头截断会丢）");
        assertTrue(result.contains("truncated middle"), "应带中间省略标记");
        assertTrue(result.contains("original=" + longMsg.length() + " chars"), "标记应含原长度");
    }

    @Test
    void nullExceptionMessagePreserved() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exceptionMessage", null);

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        assertNull(optimized.get("exceptionMessage"), "null 入参不应被改成 \"null\"");
    }

    @Test
    void longMdcTruncatedWithMarker() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("mdc", "traceId=abc&userId=42&payload=" + "z".repeat(40));

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);

        String result = (String) optimized.get("mdc");
        assertTrue(result.contains("truncated middle"));
        assertTrue(result.contains("original="));
    }

    @Test
    void mdcLineAwareTruncationKeepsWholeLines() {
        // mdc 渲染成 key=value\n 逐行后，DoS 触发时 head+tail 应按整行切，无半行
        StringBuilder mdc = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            mdc.append("k").append(i).append("=v").append(i).append("\n");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("mdc", mdc.toString());

        Map<String, Object> optimized = optimizer.optimizePromptVars(vars);
        String result = (String) optimized.get("mdc");

        assertTrue(result.contains("truncated middle"), "应触发截断");
        assertTrue(result.startsWith("k0=v0"), "head 应保留首行 k0=v0");
        assertTrue(result.endsWith("k29=v29\n"), "tail 应保留末行 k29=v29");

        // marker 前的 head 段、marker 后的 tail 段，每行都应是完整 key=value，无半行
        String marker = "...[truncated middle, original=";
        int markerStart = result.indexOf(marker);
        int markerEnd = result.indexOf("]...", markerStart) + "]...".length();
        String headPart = result.substring(0, markerStart);
        String tailPart = result.substring(markerEnd);
        for (String line : headPart.split("\n")) {
            if (!line.isEmpty()) {
                assertTrue(line.matches("k\\d+=v\\d+"), "head 行应完整（无半行）: " + line);
            }
        }
        for (String line : tailPart.split("\n")) {
            if (!line.isEmpty()) {
                assertTrue(line.matches("k\\d+=v\\d+"), "tail 行应完整（无半行）: " + line);
            }
        }
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
        String longMsg = "HEAD" + "x".repeat(30) + "TAIL";
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
    void longToolResultKeepsHeadAndTail() {
        String raw = "HEAD" + "-".repeat(30) + "ROOTCAUSE";

        String result = optimizer.truncateToolResult(raw, "queryTraceContext");

        assertTrue(result.contains("HEAD"), "应保留首部");
        assertTrue(result.contains("ROOTCAUSE"), "应保留尾部（trace 故障时刻在尾部）");
        assertTrue(result.contains("truncated middle"));
        assertTrue(result.contains("original=" + raw.length() + " chars"));
    }

    @Test
    void toolResultAtExactLimitNotTruncated() {
        String raw = "01234567890123456789"; // 恰好 20 chars == MAX_FIELD

        String result = optimizer.truncateToolResult(raw, "querySimilarHistory");

        assertEquals("01234567890123456789", result, "长度等于上限不应截断");
        assertFalse(result.contains("truncated"));
    }

    @Test
    void nullToolResultReturnsNull() {
        String result = optimizer.truncateToolResult(null, "queryTraceContext");

        assertNull(result);
    }
}

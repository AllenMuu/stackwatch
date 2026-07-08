package com.stackwatch.domain;

/**
 * 规范化栈帧。
 * 对应 B1 设计：②预处理层指纹生成的输入单元。
 */
public record StackFrame(
    String className,
    String methodName,
    String rawLine
) {
    /**
     * 解析原始堆栈行（如 "at com.foo.Bar.process(Bar.java:42)"）为 StackFrame。
     */
    public static StackFrame parse(String rawLine) {
        String trimmed = rawLine == null ? "" : rawLine.trim();
        if (trimmed.startsWith("at ")) {
            trimmed = trimmed.substring(3);
        }
        int parenIdx = trimmed.indexOf('(');
        String full = parenIdx > 0 ? trimmed.substring(0, parenIdx) : trimmed;
        int dotIdx = full.lastIndexOf('.');
        if (dotIdx < 0) {
            return new StackFrame(full, "", rawLine);
        }
        return new StackFrame(
            full.substring(0, dotIdx),
            full.substring(dotIdx + 1),
            rawLine
        );
    }

    /**
     * 规范化 key：类名#方法名，去掉行号，用于指纹和 embedding。
     */
    public String normalized() {
        return className + "#" + methodName;
    }
}

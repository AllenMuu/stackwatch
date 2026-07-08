package com.stackwatch.preprocess;

import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ErrorFingerprint;
import com.stackwatch.domain.FingerprintRecordPart;
import com.stackwatch.domain.FingerprintVersion;
import com.stackwatch.domain.StackFrame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 错误指纹生成器。
 * 对应 B1 设计：②预处理层核心，产出 L1 精确归并的 key。
 *
 * 修正点（借鉴 PostHog）：
 * 1. SHA-256（替代 SHA-1，碰撞域更大）
 * 2. {@link FingerprintRecordPart} 可解释记录（user-facing 可查"指纹由什么组成"）
 * 3. {@link FingerprintVersion} 版本化（算法升级不断历史归并关系）
 *
 * 指纹输入 = 异常类型 + 规范化 top-N 业务栈帧。
 * 过滤框架帧（java./spring./netty. 等）的原因：同一 NPE 的框架内部栈帧
 * 会因依赖版本变化而抖动，但业务代码帧稳定，指纹要"业务语义稳定"。
 */
public final class Fingerprinter {

    private static final List<String> FRAMEWORK_PREFIXES = List.of(
        "java.", "javax.", "jakarta.", "sun.", "jdk.",
        "org.springframework.", "org.apache.",
        "com.alibaba.fastjson", "io.netty.", "reactor.core."
    );

    private final int topN;
    private final FingerprintVersion version;

    public Fingerprinter(int topN) {
        this(topN, FingerprintVersion.V1);
    }

    public Fingerprinter(int topN, FingerprintVersion version) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be positive: " + topN);
        }
        this.topN = topN;
        this.version = version;
    }

    public ErrorFingerprint generate(ErrorEvent event) {
        List<String> appFrames = event.stackTrace().stream()
            .map(StackFrame::parse)
            .filter(f -> isApplicationCode(f.className()))
            .limit(topN)
            .map(StackFrame::normalized)
            .toList();

        // 兜底：应用帧不足时回退到栈顶 N 帧，保证指纹仍可生成
        if (appFrames.size() < 2) {
            appFrames = event.stackTrace().stream()
                .map(StackFrame::parse)
                .limit(topN)
                .map(StackFrame::normalized)
                .toList();
        }

        String fingerprintInput = event.exceptionType() + "\n" + String.join("\n", appFrames);
        String hash = sha256(fingerprintInput);

        List<FingerprintRecordPart> record = List.of(
            FingerprintRecordPart.exception(event.exceptionType()),
            FingerprintRecordPart.frame(appFrames)
        );

        return new ErrorFingerprint(hash, version, appFrames, record);
    }

    /**
     * 生成用于 embedding 的文本（带 rendering 策略，借鉴 PostHog）。
     */
    public String buildEmbeddingText(ErrorEvent event, EmbeddingRendering rendering) {
        List<String> frames = event.stackTrace().stream()
            .map(StackFrame::parse)
            .filter(f -> isApplicationCode(f.className()))
            .limit(topN)
            .map(f -> rendering == EmbeddingRendering.WITH_ERROR_MESSAGE
                ? f.normalized() + " | " + event.exceptionMessage()
                : f.normalized())
            .toList();
        return event.exceptionType() + "\n" + String.join("\n", frames);
    }

    private static boolean isApplicationCode(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        return FRAMEWORK_PREFIXES.stream().noneMatch(className::startsWith);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

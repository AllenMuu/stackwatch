package com.stackwatch.domain;

import java.util.List;

/**
 * 指纹记录的组成片段（user-facing 可解释）。
 * 借鉴 PostHog FingerprintRecordPart：记录指纹由哪几帧/哪部分组成，
 * 便于调试"为什么这两条异常没归并到一起"。
 */
public record FingerprintRecordPart(
    PartType type,
    List<String> pieces
) {
    public enum PartType {
        FRAME,
        EXCEPTION,
        CUSTOM
    }

    public FingerprintRecordPart {
        pieces = pieces == null ? List.of() : List.copyOf(pieces);
    }

    public static FingerprintRecordPart frame(List<String> pieces) {
        return new FingerprintRecordPart(PartType.FRAME, pieces);
    }

    public static FingerprintRecordPart exception(String exceptionType) {
        return new FingerprintRecordPart(PartType.EXCEPTION, List.of(exceptionType));
    }
}

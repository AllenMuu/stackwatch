package com.stackwatch.domain;

import java.util.List;

/**
 * 错误指纹：L1 精确归并的 key。
 * 对应 B1 设计：②预处理层 Fingerprinter 产出。
 * 修正点：SHA-256 + 可解释记录 + 版本化。
 */
public record ErrorFingerprint(
    String hash,
    FingerprintVersion version,
    List<String> topFrames,
    List<FingerprintRecordPart> record
) {
    public ErrorFingerprint {
        topFrames = topFrames == null ? List.of() : List.copyOf(topFrames);
        record = record == null ? List.of() : List.copyOf(record);
    }
}

package com.stackwatch.domain;

import java.util.List;

/**
 * 错误指纹：L1 精确归并的 key。
 * 对应 B1 设计：②预处理层 Fingerprinter 产出。
 * 修正点：SHA-256 + 可解释记录 + 版本化。
 */
public record ErrorFingerprint(
    Identity identity,
    List<String> topFrames,
    List<FingerprintRecordPart> record
) {
    public ErrorFingerprint {
        if (identity == null) {
            throw new IllegalArgumentException("identity is required");
        }
        topFrames = topFrames == null ? List.of() : List.copyOf(topFrames);
        record = record == null ? List.of() : List.copyOf(record);
    }

    public ErrorFingerprint(
        String hash,
        FingerprintVersion version,
        List<String> topFrames,
        List<FingerprintRecordPart> record) {
        this(new Identity(hash, null, version), topFrames, record);
    }

    public static ErrorFingerprint v2(
        String strictHash,
        String looseHash,
        List<String> topFrames,
        List<FingerprintRecordPart> record) {
        return new ErrorFingerprint(
            new Identity(strictHash, looseHash, FingerprintVersion.V2), topFrames, record);
    }

    public String hash() {
        return identity.hash();
    }

    public String looseHash() {
        return identity.looseHash();
    }

    public FingerprintVersion version() {
        return identity.version();
    }

    public record Identity(String hash, String looseHash, FingerprintVersion version) {
    }
}

package com.stackwatch.preprocess;

import com.stackwatch.config.FingerprintProperties;
import com.stackwatch.domain.ErrorEvent;
import com.stackwatch.domain.ThrowableInfo;

/** Turns raw throwable facts into the explicit deterministic V2 identity model. */
public final class ErrorNormalizer {
    private final CauseResolver causeResolver;
    private final MessageNormalizer messageNormalizer;
    private final StackFrameNormalizer stackFrameNormalizer;

    public ErrorNormalizer(FingerprintProperties properties, int topN) {
        this(
            new CauseResolver(properties),
            new MessageNormalizer(properties),
            new StackFrameNormalizer(properties, topN));
    }

    public ErrorNormalizer(
        CauseResolver causeResolver,
        MessageNormalizer messageNormalizer,
        StackFrameNormalizer stackFrameNormalizer) {
        this.causeResolver = causeResolver;
        this.messageNormalizer = messageNormalizer;
        this.stackFrameNormalizer = stackFrameNormalizer;
    }

    public NormalizedError normalize(ErrorEvent event) {
        CauseResolver.ResolvedCause resolved = causeResolver.resolve(event.exception());
        ThrowableInfo outer = resolved.outer();
        ThrowableInfo effective = resolved.effective();
        return new NormalizedError(
            outer.type(),
            effective.type(),
            messageNormalizer.normalize(outer.message()),
            messageNormalizer.normalize(effective.message()),
            stackFrameNormalizer.select(outer, effective),
            stackFrameNormalizer.normalizedFrames(effective),
            resolved.depth());
    }
}

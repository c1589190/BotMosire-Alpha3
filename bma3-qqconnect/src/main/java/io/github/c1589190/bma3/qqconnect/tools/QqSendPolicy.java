package io.github.c1589190.bma3.qqconnect.tools;

import io.github.c1589190.bma3.core.message.TextSendPolicy;

/** QQ view of the shared provider-neutral text policy. */
public record QqSendPolicy(int maxTextChars, String overflow) {

    public QqSendPolicy {
        TextSendPolicy delegate = new TextSendPolicy(maxTextChars, overflow);
        maxTextChars = delegate.maxTextChars();
        overflow = delegate.overflow();
    }

    public static QqSendPolicy defaults() {
        TextSendPolicy policy = TextSendPolicy.defaults();
        return new QqSendPolicy(policy.maxTextChars(), policy.overflow());
    }

    public static QqSendPolicy from(int maxTextChars, String overflow) {
        return new QqSendPolicy(maxTextChars, overflow);
    }

    /** Shared implementation; all chunking/codepoint rules have exactly one owner. */
    public TextSendPolicy textPolicy() {
        return new TextSendPolicy(maxTextChars, overflow);
    }
}

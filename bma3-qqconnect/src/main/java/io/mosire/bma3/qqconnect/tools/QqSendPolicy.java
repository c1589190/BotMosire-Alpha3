package io.mosire.bma3.qqconnect.tools;

/** Hard limits shared by every text send tool. */
public record QqSendPolicy(int maxTextChars, String overflow) {

    public QqSendPolicy {
        overflow = overflow == null || overflow.isBlank() ? "reject" : overflow;
        if (maxTextChars < 1 || maxTextChars > 3000) {
            throw new IllegalArgumentException("maxTextChars 必须在 1..3000");
        }
        if (!"reject".equals(overflow) && !"split".equals(overflow)) {
            throw new IllegalArgumentException("overflow 必须是 reject/split");
        }
    }

    public static QqSendPolicy defaults() {
        return new QqSendPolicy(3000, "reject");
    }

    public static QqSendPolicy from(int maxTextChars, String overflow) {
        return new QqSendPolicy(maxTextChars, overflow);
    }
}

package io.github.c1589190.bma3.app.channel.matching;

import java.util.Locale;

/** Channel names used by the pure cross-channel matcher. */
public enum Channel {
    ONEBOT("onebot"),
    QQ_OFFICIAL("qq_official");

    private final String wireName;

    Channel(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Channel fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("channel 不能为空");
        }
        String normalized = value.strip();
        for (Channel channel : values()) {
            if (channel.wireName.equalsIgnoreCase(normalized)
                    || channel.name().equalsIgnoreCase(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知 channel");
    }

    /** Stable lower-case spelling for deterministic summaries. */
    public String stableName() {
        return wireName.toLowerCase(Locale.ROOT);
    }
}

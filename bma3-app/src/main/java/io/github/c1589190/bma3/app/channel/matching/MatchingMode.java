package io.github.c1589190.bma3.app.channel.matching;

/**
 * Correlation policy supplied by the persisted logical-thread setting.
 *
 * <p>This type deliberately lives in the pure matching package. The caller maps
 * {@code QqChannelRegistry.MatchingMode} to this value without making the matcher depend on a
 * database or the composition root.</p>
 */
public enum MatchingMode {
    STRICT("strict"),
    LOOSE("loose"),
    OFF("off");

    private final String wireName;

    MatchingMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MatchingMode fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("matching mode 不能为空");
        }
        String normalized = value.strip();
        for (MatchingMode mode : values()) {
            if (mode.wireName.equalsIgnoreCase(normalized)
                    || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知 matching mode");
    }
}

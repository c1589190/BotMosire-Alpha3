package io.mosire.bma3.qqofficial.event;

/**
 * First-version QQ official Gateway dispatch types understood by BMA3.
 *
 * <p>The wire name is preserved separately on decoded events so an unknown future event never
 * gets silently coerced into a known type. Message events carry content; system events only
 * carry directory/switch metadata.</p>
 */
public enum OfficialQqEventType {
    C2C_MESSAGE_CREATE(Kind.MESSAGE),
    GROUP_AT_MESSAGE_CREATE(Kind.MESSAGE),
    GROUP_MESSAGE_CREATE(Kind.MESSAGE),
    READY(Kind.SYSTEM),
    RESUMED(Kind.SYSTEM),
    FRIEND_ADD(Kind.SYSTEM),
    FRIEND_DEL(Kind.SYSTEM),
    GROUP_ADD_ROBOT(Kind.SYSTEM),
    GROUP_DEL_ROBOT(Kind.SYSTEM),
    GROUP_MSG_RECEIVE(Kind.SYSTEM),
    GROUP_MSG_REJECT(Kind.SYSTEM);

    /** Coarse category used by the decoder; unknown events never belong to a known category. */
    public enum Kind {
        MESSAGE,
        SYSTEM
    }

    private final Kind kind;

    OfficialQqEventType(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isMessage() {
        return kind == Kind.MESSAGE;
    }

    public boolean isSystem() {
        return kind == Kind.SYSTEM;
    }

    /** Returns {@code null} for a dispatch name not in this release's vocabulary. */
    public static OfficialQqEventType fromWire(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return null;
        }
        for (OfficialQqEventType value : values()) {
            if (value.name().equals(wireName)) {
                return value;
            }
        }
        return null;
    }
}

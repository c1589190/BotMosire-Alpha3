package io.github.c1589190.bma3.qqofficial.account;

import io.github.c1589190.bma3.qqofficial.event.OfficialQqDispatch;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEventType;
import java.util.Objects;

/**
 * One decoded event together with the account that owns the runtime which produced it.
 *
 * <p>The account id is attached by the registry from the holder that supplied the sink. A factory
 * or transport cannot choose a different account id, which prevents cross-account event routing.
 * </p>
 */
public record OfficialQqAccountEvent(String accountId, OfficialQqDispatch dispatch) {

    public OfficialQqAccountEvent {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        accountId = accountId.strip();
        Objects.requireNonNull(dispatch, "dispatch");
    }

    @Override
    public String toString() {
        // The dispatch envelope is upstream-controlled. Only a fixed known-type whitelist and
        // counts may cross into a loggable string; ids/types can otherwise carry body text,
        // credentials or newline injection.
        OfficialQqEventType known = OfficialQqEventType.fromWire(dispatch.eventType());
        String safeType = known == null ? "UNKNOWN" : known.name();
        return "OfficialQqAccountEvent[accountId="
                + OfficialQqAccountRuntimeStatus.safe(accountId)
                + ", eventType=" + safeType
                + ", eventIdLength=" + safeLength(dispatch.eventId())
                + ", sequence=" + dispatch.sequence() + "]";
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}

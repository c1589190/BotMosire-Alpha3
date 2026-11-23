package io.github.c1589190.bma3.qqofficial.account;

/**
 * Stable account-level state exposed by the official QQ desired-state registry.
 *
 * <p>The values deliberately mirror {@link io.github.c1589190.bma3.qqofficial.gateway.GatewayState} so a
 * production factory can adapt the gateway snapshot without inventing a second state machine.
 * {@link #DISABLED} is registry-owned and means the operator asked for the account but no
 * credential was resolved and no network client was created.</p>
 */
public enum OfficialQqAccountState {
    DISABLED,
    STOPPED,
    CONNECTING,
    HELLO,
    IDENTIFYING,
    RESUME,
    READY,
    RECONNECTING,
    DOWN,
    CLOSED;

    /** True only for the state in which a live gateway is usable for this account. */
    public boolean connected() {
        return this == READY;
    }

    /** Terminal means the registry must not silently create another runtime for this state. */
    public boolean terminal() {
        return this == DOWN || this == CLOSED;
    }
}

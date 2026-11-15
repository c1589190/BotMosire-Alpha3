package io.mosire.bma3.core.message;

/**
 * Persisted delivery state of an outbound message-board row.
 *
 * <p>Inbound rows have no delivery state at all. {@code UNKNOWN} means the provider may have
 * executed the send; it must stay visible and must never be retried automatically.
 * {@code LEGACY_UNKNOWN} only describes historical outbound rows written before delivery state
 * existed: they carry no reliable outcome, which is neither a failure nor a licence to resend.</p>
 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED_BEFORE_SEND,
    UNKNOWN,
    LEGACY_UNKNOWN;

    /** True for states that may legally close a {@link #PENDING} outbound row. */
    public boolean terminal() {
        return this == SENT || this == FAILED_BEFORE_SEND || this == UNKNOWN;
    }
}

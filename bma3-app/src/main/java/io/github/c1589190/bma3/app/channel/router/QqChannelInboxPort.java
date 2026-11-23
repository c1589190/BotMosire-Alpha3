package io.github.c1589190.bma3.app.channel.router;

import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.InboxEvidenceUpdate;
import io.github.c1589190.bma3.inbox.core.InboxItem;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.InboxStore;
import java.util.Objects;
import java.util.Optional;

/** Narrow Inbox seam used by the router. */
public interface QqChannelInboxPort {

    InboxItem appendProjectedOnce(String requestKey, AppendItem item);

    InboxItem appendOrMerge(AppendItem item);

    InboxItem appendEvidenceOnly(InboxEvidenceUpdate update);

    Optional<InboxItem> item(String itemId);

    Optional<InboxItem> activeForSource(String source);

    /**
     * Persist a body-free suppression tombstone for an unbound raw message that passed the drop
     * gate while its source gate was off. The same request key later makes
     * {@link #appendProjectedOnce(String, AppendItem)} a no-op even after the gate is re-enabled.
     */
    void suppressRawOnce(String requestKey, String source, String channel);

    /**
     * Read-only check for an exact raw gate-off tombstone. A receipt with another kind (for
     * example a successful append receipt) is not a tombstone and must return false.
     */
    boolean isRawSuppressed(String requestKey, String source, String channel);

    static QqChannelInboxPort from(InboxService service, InboxStore store) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(store, "store");
        return new QqChannelInboxPort() {
            @Override
            public InboxItem appendProjectedOnce(String requestKey, AppendItem item) {
                return service.appendProjectedOnce(requestKey, item);
            }

            @Override
            public InboxItem appendOrMerge(AppendItem item) {
                return service.appendOrMerge(item);
            }

            @Override
            public InboxItem appendEvidenceOnly(InboxEvidenceUpdate update) {
                return service.appendEvidenceOnly(update);
            }

            @Override
            public Optional<InboxItem> item(String itemId) {
                return store.item(itemId);
            }

            @Override
            public Optional<InboxItem> activeForSource(String source) {
                return store.activeForSource(source);
            }

            @Override
            public void suppressRawOnce(String requestKey, String source, String channel) {
                if (store.claimReceipt(requestKey, "", "raw_suppressed_gate_off", source, channel)) {
                    return;
                }
                var receipt = store.receipt(requestKey).orElseThrow(() ->
                        new IllegalStateException("raw suppression receipt metadata missing"));
                if (!source.equals(receipt.source()) || !channel.equals(receipt.channel())) {
                    throw new IllegalArgumentException(
                            "raw suppression receipt source/channel conflict");
                }
            }

            @Override
            public boolean isRawSuppressed(String requestKey, String source, String channel) {
                return store.receipt(requestKey)
                        .map(receipt -> "raw_suppressed_gate_off".equals(receipt.kind())
                                && source.equals(receipt.source())
                                && channel.equals(receipt.channel()))
                        .orElse(false);
            }
        };
    }
}

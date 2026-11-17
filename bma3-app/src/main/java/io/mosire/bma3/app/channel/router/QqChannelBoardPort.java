package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.AppendResult;
import io.mosire.bma3.core.store.MessageBoardStore;
import java.util.Objects;
import java.util.Optional;

/** Narrow board seam used by the router; production delegates to {@link MessageBoardStore}. */
public interface QqChannelBoardPort {

    AppendResult append(InboundMessage message);

    Optional<Message> findInbound(SourceAddress source, String messageId);

    long maxLocalSeq(SourceAddress source);

    static QqChannelBoardPort from(MessageBoardStore store) {
        Objects.requireNonNull(store, "store");
        return new QqChannelBoardPort() {
            @Override
            public AppendResult append(InboundMessage message) {
                return store.append(message);
            }

            @Override
            public Optional<Message> findInbound(SourceAddress source, String messageId) {
                return store.findInbound(source, messageId);
            }

            @Override
            public long maxLocalSeq(SourceAddress source) {
                return store.maxLocalSeq(source);
            }
        };
    }
}

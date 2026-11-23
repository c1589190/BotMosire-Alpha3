package io.github.c1589190.bma3.app.channel.tools;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import java.util.Objects;

/** compare/history 的只读依赖；由后续 router 统一注入，不在此处装配。 */
record ChannelEvidenceToolContext(
        QqChannelRegistry registry,
        QqChannelObservationStore observations,
        MessageBoardStore board) {

    ChannelEvidenceToolContext {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(board, "board");
    }
}

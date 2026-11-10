package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.store.ConsumerConversationStore;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Shared dependencies for QQ AgentTools. */
public record QqToolContext(
        AccountRegistry accounts,
        MessageBoardStore board,
        ConsumerConversationStore consumers,
        Path imageRoot,
        QqExtrasStore extras,
        Supplier<QqSendPolicy> sendPolicy) {

    public QqToolContext {
        sendPolicy = sendPolicy == null ? QqSendPolicy::defaults : sendPolicy;
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers) {
        this(accounts, board, consumers, Path.of(".").toAbsolutePath().normalize(), null, null);
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers, Path imageRoot) {
        this(accounts, board, consumers, imageRoot, null, null);
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers, Path imageRoot,
                         QqExtrasStore extras) {
        this(accounts, board, consumers, imageRoot, extras, null);
    }

    public QqSendPolicy policy() {
        QqSendPolicy policy = sendPolicy.get();
        return policy == null ? QqSendPolicy.defaults() : policy;
    }
}

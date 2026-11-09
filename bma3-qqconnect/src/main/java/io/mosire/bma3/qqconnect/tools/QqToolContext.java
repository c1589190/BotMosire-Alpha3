package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.store.ConsumerConversationStore;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import java.nio.file.Path;

/** Shared dependencies for QQ AgentTools. */
public record QqToolContext(
        AccountRegistry accounts,
        MessageBoardStore board,
        ConsumerConversationStore consumers,
        Path imageRoot) {

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers) {
        this(accounts, board, consumers, Path.of(".").toAbsolutePath().normalize());
    }
}

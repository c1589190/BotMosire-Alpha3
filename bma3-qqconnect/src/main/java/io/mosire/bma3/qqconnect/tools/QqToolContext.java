package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.store.ConsumerConversationStore;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountRegistry;

/** Shared dependencies for QQ AgentTools. */
public record QqToolContext(
        AccountRegistry accounts,
        MessageBoardStore board,
        ConsumerConversationStore consumers) {
}

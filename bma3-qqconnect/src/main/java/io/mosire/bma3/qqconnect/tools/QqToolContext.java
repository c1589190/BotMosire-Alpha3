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
        Supplier<QqSendPolicy> sendPolicy,
        Supplier<QqMediaPolicy> mediaPolicy,
        io.mosire.bma3.qqconnect.directory.QqDirectoryStore directory) {

    public QqToolContext {
        sendPolicy = sendPolicy == null ? QqSendPolicy::defaults : sendPolicy;
        mediaPolicy = mediaPolicy == null
                ? () -> QqMediaPolicy.defaults(imageRoot == null ? Path.of(".") : imageRoot)
                : mediaPolicy;
    }

    public QqMediaPolicy media() {
        QqMediaPolicy policy = mediaPolicy.get();
        return policy == null
                ? QqMediaPolicy.defaults(imageRoot == null ? Path.of(".") : imageRoot)
                : policy;
    }

    /** Compatibility constructor: no directory cache yet. */
    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers, Path imageRoot,
                         QqExtrasStore extras, Supplier<QqSendPolicy> sendPolicy,
                         Supplier<QqMediaPolicy> mediaPolicy) {
        this(accounts, board, consumers, imageRoot, extras, sendPolicy, mediaPolicy, null);
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers) {
        this(accounts, board, consumers, Path.of(".").toAbsolutePath().normalize(), null, null,
                null, null);
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers, Path imageRoot) {
        this(accounts, board, consumers, imageRoot, null, null, null);
    }

    public QqToolContext(AccountRegistry accounts, MessageBoardStore board,
                         ConsumerConversationStore consumers, Path imageRoot,
                         QqExtrasStore extras) {
        this(accounts, board, consumers, imageRoot, extras, null, null);
    }

    public QqSendPolicy policy() {
        QqSendPolicy policy = sendPolicy.get();
        return policy == null ? QqSendPolicy.defaults() : policy;
    }
}

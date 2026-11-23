package io.github.c1589190.bma3.qqofficial.readtools;

import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRegistry;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import java.util.Objects;

/** Dependencies injected into the official read-tool factory. */
public record OfficialQqReadContext(
        MessageBoardStore board,
        OfficialQqDirectoryStore directory,
        OfficialQqAccountResolver accountResolver,
        OfficialQqReadApiProvider api) {

    public OfficialQqReadContext {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(accountResolver, "accountResolver");
        api = api == null ? OfficialQqReadApiProvider.unavailable() : api;
    }

    public static OfficialQqReadContext fromRegistry(
            OfficialQqAccountRegistry registry,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            OfficialQqReadApiProvider api) {
        return new OfficialQqReadContext(board, directory,
                new OfficialQqAccountResolver(OfficialQqAccountView.fromRegistry(registry)), api);
    }
}

package io.github.c1589190.bma3.app.channel.tools;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import java.time.Clock;
import java.util.Objects;

/** 渠道控制工具共享的只读依赖；写操作全部委托给 registry。 */
record ChannelToolContext(
        QqChannelRegistry registry,
        ChannelSourceCatalog sourceCatalog,
        ChannelMutationGate mutationGate,
        BindingOperations bindingOperations,
        Clock clock) {

    ChannelToolContext {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(sourceCatalog, "sourceCatalog");
        Objects.requireNonNull(mutationGate, "mutationGate");
        Objects.requireNonNull(bindingOperations, "bindingOperations");
        Objects.requireNonNull(clock, "clock");
    }
}

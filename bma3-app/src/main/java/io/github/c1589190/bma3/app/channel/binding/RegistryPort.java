package io.github.c1589190.bma3.app.channel.binding;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ActiveBindingPage;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.BindResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.UnbindResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow adapter over {@link QqChannelRegistry}.
 *
 * <p>Production always delegates to the real registry. The seam exists so coordinator tests can
 * inject the exact commit-after-throw / subsequent-read-fails failure mode that a real SQLite
 * connection quarantine can produce, without changing the owner's class or bypassing its writes.</p>
 */
interface RegistryPort {

    Optional<ChannelBinding> binding(String rawSource);

    BindResult bind(String threadId, String rawSource,
                    QqChannelRegistry.Channel expectedChannel, String label);

    UnbindResult unbind(String threadId, String rawSource);

    Optional<LogicalThread> thread(String threadId);

    List<ChannelBinding> bindings(String threadId);

    ActiveBindingPage activeBindings(String afterRawSource, int limit);

    static RegistryPort from(QqChannelRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return new RegistryPort() {
            @Override
            public Optional<ChannelBinding> binding(String rawSource) {
                return registry.binding(rawSource);
            }

            @Override
            public BindResult bind(String threadId, String rawSource,
                                   QqChannelRegistry.Channel expectedChannel, String label) {
                return registry.bind(threadId, rawSource, expectedChannel, label);
            }

            @Override
            public UnbindResult unbind(String threadId, String rawSource) {
                return registry.unbind(threadId, rawSource);
            }

            @Override
            public Optional<LogicalThread> thread(String threadId) {
                return registry.thread(threadId);
            }

            @Override
            public List<ChannelBinding> bindings(String threadId) {
                return registry.bindings(threadId);
            }

            @Override
            public ActiveBindingPage activeBindings(String afterRawSource, int limit) {
                return registry.activeBindings(afterRawSource, limit);
            }
        };
    }
}

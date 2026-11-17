package io.mosire.bma3.app.channel.tools;

import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.BindResult;
import io.mosire.bma3.app.channel.QqChannelRegistry.UnbindResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 仅保留给旧独立工具测试的 registry-direct 实现。
 *
 * <p>它没有 ledger generation/startBoardSeq，返回 {@code coordinated=false}。生产工厂不会
 * 自动构造此类；生产必须显式注入 {@code QqBindingCoordinator}。</p>
 */
final class RegistryBindingOperations implements BindingOperations {

    private final QqChannelRegistry registry;

    RegistryBindingOperations(QqChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public BindingResult bind(String threadId, String rawSource, String label) {
        BindResult outcome = registry.bind(threadId, rawSource, null, label);
        return new BindingResult(outcome.thread().threadId(), outcome.binding().rawSource(),
                outcome.changed(), false, OptionalLong.empty(), OptionalLong.empty(),
                Optional.empty(), Optional.empty());
    }

    @Override
    public BindingResult unbind(String threadId, String rawSource) {
        UnbindResult outcome = registry.unbind(threadId, rawSource);
        return new BindingResult(outcome.thread().threadId(), rawSource, outcome.changed(),
                false, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(),
                Optional.empty());
    }
}

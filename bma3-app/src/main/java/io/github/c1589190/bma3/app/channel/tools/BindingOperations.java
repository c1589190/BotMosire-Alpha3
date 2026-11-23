package io.github.c1589190.bma3.app.channel.tools;

import io.github.c1589190.bma3.app.channel.binding.QqBindingCoordinator;
import io.github.c1589190.bma3.app.channel.binding.QqBindingCoordinator.BindOutcome;
import io.github.c1589190.bma3.app.channel.binding.QqBindingCoordinator.UnbindOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 工具层可注入的绑定操作端口。
 *
 * <p>生产实现必须是 {@link QqBindingCoordinator} 适配器：bind/unbind 由 coordinator 维护
 * registry+ledger 的 journal/epoch/startBoardSeq。该接口只暴露 bind/unbind，不暴露 registry，
 * 避免工具自行绕过协调。</p>
 */
public interface BindingOperations {

    BindingResult bind(String threadId, String rawSource, String label);

    BindingResult unbind(String threadId, String rawSource);

    /** 生产适配器；coordinator 必须与工具工厂使用同一个 ChannelMutationGate。 */
    static BindingOperations from(QqBindingCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        return new BindingOperations() {
            @Override
            public BindingResult bind(String threadId, String rawSource, String label) {
                BindOutcome outcome = coordinator.bind(threadId, rawSource, label);
                return new BindingResult(outcome.threadId(), outcome.rawSource(), outcome.changed(),
                        true, OptionalLong.of(outcome.generation()),
                        OptionalLong.of(outcome.startBoardSeq()), Optional.empty(),
                        Optional.empty());
            }

            @Override
            public BindingResult unbind(String threadId, String rawSource) {
                UnbindOutcome outcome = coordinator.unbind(threadId, rawSource);
                return new BindingResult(outcome.threadId(), outcome.rawSource(), outcome.changed(),
                        true, OptionalLong.empty(), OptionalLong.empty(),
                        Optional.of(outcome.ledgerUnbound()), Optional.of(outcome.complete()));
            }
        };
    }

    /** Profile-neutral binding result; ledger fields are absent for explicit legacy test wiring. */
    record BindingResult(
            String threadId,
            String rawSource,
            boolean changed,
            boolean coordinated,
            OptionalLong ledgerGeneration,
            OptionalLong startBoardSeq,
            Optional<Boolean> ledgerUnbound,
            Optional<Boolean> complete) {

        public BindingResult {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(rawSource, "rawSource");
            ledgerGeneration = ledgerGeneration == null ? OptionalLong.empty() : ledgerGeneration;
            startBoardSeq = startBoardSeq == null ? OptionalLong.empty() : startBoardSeq;
            ledgerUnbound = ledgerUnbound == null ? Optional.empty() : ledgerUnbound;
            complete = complete == null ? Optional.empty() : complete;
        }
    }
}

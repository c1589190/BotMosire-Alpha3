package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ActiveBindingRef;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ChannelAvailability;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.MatchingMode;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.SourceBinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Production control snapshot adapter over the existing registry and observation ledger.
 *
 * <p>Callers must hold the shared mutation gate. This adapter only reads; it never changes a
 * binding, gate, preferred channel or identity mapping.</p>
 */
public final class RegistryQqChannelControl implements QqChannelControl {

    private final QqChannelRegistry registry;
    private final QqChannelObservationLedger ledger;

    public RegistryQqChannelControl(QqChannelRegistry registry,
                                    QqChannelObservationLedger ledger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public SourceState sourceState(String rawSource, String senderId, Channel incomingChannel) {
        Objects.requireNonNull(rawSource, "rawSource");
        Objects.requireNonNull(incomingChannel, "incomingChannel");
        boolean sourceGate = registry.sourceInjectionEnabled(rawSource);
        Optional<QqChannelRegistry.SourceStatus> status = registry.sourceStatus(rawSource);
        if (status.isEmpty()) {
            return SourceState.unbound(sourceGate);
        }
        if (status.orElseThrow().binding().state()
                == QqChannelRegistry.BindingState.PENDING_OBSERVATION) {
            // Reaching this method means a priority-admitted real ingress is being routed under
            // the shared gate. That ingress is the observation which activates a pre-bound source.
            registry.markSourceObserved(rawSource);
            status = registry.sourceStatus(rawSource);
        }
        QqChannelRegistry.SourceStatus sourceStatus = status.orElseThrow();
        QqChannelRegistry.ChannelBinding registryBinding = sourceStatus.binding();
        QqChannelRegistry.LogicalThread thread = sourceStatus.thread();
        if (registryBinding.channel().wireName().equals("onebot")
                != (incomingChannel == Channel.ONEBOT)) {
            throw new QqChannelRouterException(QqChannelRouterException.Code.INVALID_INPUT,
                    "raw source channel 与 registry binding 不一致");
        }
        SourceBinding persistedBinding = ledger.sourceBinding(rawSource).orElseThrow(() ->
                new QqChannelRouterException(
                        QqChannelRouterException.Code.BINDING_LEDGER_MISMATCH,
                        "registry binding 存在但 observation ledger 无 source binding"));
        if (!persistedBinding.active()
                || !persistedBinding.threadId().equals(thread.threadId())
                || !persistedBinding.accountId().equals(registryBinding.accountId())
                || persistedBinding.channel() != incomingChannel) {
            throw new QqChannelRouterException(
                    QqChannelRouterException.Code.BINDING_LEDGER_MISMATCH,
                    "registry 与 observation ledger 的 binding tuple 不一致");
        }

        List<ActiveBindingRef> activeBindings = activeBindings(thread.threadId());
        String epoch = QqChannelObservationStore.bindingEpochId(thread.threadId(), activeBindings);
        IdentityResolution identity = resolveIdentity(thread.threadId(), rawSource,
                incomingChannel, senderId);
        Channel preferred = registryChannel(thread.preferredChannel());
        ChannelAvailability preferredAvailability = ChannelAvailability.UNKNOWN;
        Channel selected = null;
        Optional<QqChannelRegistry.DisplayChoice> display =
                registry.status(thread.threadId()).flatMap(QqChannelRegistry.ThreadStatus::display);
        if (preferred != null) {
            boolean available = display.map(QqChannelRegistry.DisplayChoice::enabledChannels)
                    .orElse(List.of()).contains(registryChannelBack(preferred));
            preferredAvailability = available
                    ? ChannelAvailability.AVAILABLE : ChannelAvailability.UNAVAILABLE;
            if (available) {
                selected = preferred;
            } else {
                selected = display.map(choice -> storeChannel(choice.channel())).orElse(null);
            }
        } else if (sourceStatus.effectiveInjection()) {
            selected = incomingChannel;
        } else {
            selected = display.map(choice -> storeChannel(choice.channel())).orElse(null);
        }

        MatchingMode mode = switch (thread.matchingMode()) {
            case STRICT -> MatchingMode.STRICT;
            case LOOSE -> MatchingMode.LOOSE;
            case OFF -> MatchingMode.OFF;
        };
        return new SourceState(sourceGate, Optional.of(new ControlSnapshot(
                thread.threadId(), thread.label(), rawSource, incomingChannel,
                registryBinding.accountId(), persistedBinding.startBoardSeq(),
                persistedBinding.generation(), epoch, sourceStatus.effectiveInjection(),
                preferred, preferredAvailability, selected, mode, thread.revision(),
                identity.canonicalSenderId(), identity.status(), activeBindings)));
    }

    @Override
    public List<String> activeRawSources() {
        Set<String> sources = new LinkedHashSet<>();
        for (QqChannelRegistry.LogicalThread thread : registry.listThreads(500)) {
            for (QqChannelRegistry.ChannelBinding binding : registry.bindings(thread.threadId())) {
                if (binding.active()) {
                    sources.add(binding.rawSource());
                }
            }
        }
        return List.copyOf(sources);
    }

    @Override
    public List<String> activeThreadIds() {
        return registry.listThreads(500).stream()
                .map(QqChannelRegistry.LogicalThread::threadId)
                .toList();
    }

    private List<ActiveBindingRef> activeBindings(String threadId) {
        List<ActiveBindingRef> refs = new ArrayList<>();
        for (QqChannelRegistry.ChannelBinding binding : registry.bindings(threadId)) {
            if (!binding.active()) {
                continue;
            }
            SourceBinding persisted = ledger.sourceBinding(binding.rawSource()).orElseThrow(() ->
                    new QqChannelRouterException(
                            QqChannelRouterException.Code.BINDING_LEDGER_MISMATCH,
                            "registry active binding 缺少 observation generation"));
            if (!persisted.active()
                    || !persisted.threadId().equals(threadId)
                    || !persisted.accountId().equals(binding.accountId())
                    || persisted.channel() != storeChannel(binding.channel())) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.BINDING_LEDGER_MISMATCH,
                        "active binding epoch tuple 不一致");
            }
            refs.add(new ActiveBindingRef(persisted.channel(), persisted.accountId(),
                    persisted.rawSource(), persisted.generation()));
        }
        return List.copyOf(refs);
    }

    private IdentityResolution resolveIdentity(String threadId, String rawSource,
                                               Channel channel, String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return new IdentityResolution("", IdentityStatus.UNMAPPED);
        }
        Set<String> canonicals = new LinkedHashSet<>();
        for (QqChannelRegistry.ChannelIdentityBinding identity : registry.identities(threadId)) {
            if (!identity.active()
                    || storeChannel(identity.channel()) != channel
                    || !identity.channelUserId().equals(senderId)) {
                continue;
            }
            boolean applies = identity.scope().type()
                    == QqChannelRegistry.IdentityScope.Type.THREAD
                    ? identity.scope().value().equals(threadId)
                    : identity.scope().value().equals(rawSource);
            if (applies) {
                canonicals.add(identity.canonicalUserId());
            }
        }
        if (canonicals.size() == 1) {
            return new IdentityResolution(canonicals.iterator().next(), IdentityStatus.MAPPED);
        }
        return new IdentityResolution("", canonicals.isEmpty()
                ? IdentityStatus.UNMAPPED : IdentityStatus.AMBIGUOUS);
    }

    private static Channel storeChannel(QqChannelRegistry.Channel channel) {
        return channel == QqChannelRegistry.Channel.ONEBOT
                ? Channel.ONEBOT : Channel.QQ_OFFICIAL;
    }

    private static QqChannelRegistry.Channel registryChannelBack(Channel channel) {
        return channel == Channel.ONEBOT
                ? QqChannelRegistry.Channel.ONEBOT : QqChannelRegistry.Channel.QQ_OFFICIAL;
    }

    private static Channel registryChannel(QqChannelRegistry.Channel channel) {
        return channel == null ? null : storeChannel(channel);
    }

    private record IdentityResolution(String canonicalSenderId, IdentityStatus status) {
    }
}

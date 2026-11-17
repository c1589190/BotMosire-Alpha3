package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ChannelAvailability;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.MatchingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only control-plane port consumed by the router.
 *
 * <p>The production adapter reads {@code QqChannelRegistry} and the observation ledger while the
 * caller holds the shared mutation gate. Test implementations can supply deterministic snapshots.
 * A source that has no active binding is represented by {@code binding=empty}; its source-level
 * pause gate still applies to the legacy raw path.</p>
 */
public interface QqChannelControl {

    SourceState sourceState(String rawSource, String senderId, Channel incomingChannel);

    /** All raw sources with an active registry binding, used for startup intent recovery. */
    List<String> activeRawSources();

    /** All logical threads that may have pending observations, used for startup route recovery. */
    List<String> activeThreadIds();

    record SourceState(boolean sourceGateEnabled, Optional<ControlSnapshot> binding) {
        public SourceState {
            binding = binding == null ? Optional.empty() : binding;
        }

        public static SourceState unbound(boolean gateEnabled) {
            return new SourceState(gateEnabled, Optional.empty());
        }
    }

    /**
     * Immutable routing snapshot captured before the board append.
     *
     * <p>{@code selectedChannel} is the channel that may own a new canonical item at this exact
     * intent time. If the explicit preferred channel is healthy, this is the preferred channel even
     * when the current observation came from the other channel; such an observation is deferred as
     * evidence. If the preferred channel is unavailable/paused, it is the currently available
     * fallback channel. Existing open items keep their own fixed display channel.</p>
     */
    record ControlSnapshot(
            String threadId,
            String threadLabel,
            String rawSource,
            Channel channel,
            String accountId,
            long startBoardSeq,
            long bindingGeneration,
            String bindingEpochId,
            boolean gateEnabled,
            Channel preferredChannel,
            ChannelAvailability preferredChannelAvailability,
            Channel selectedChannel,
            MatchingMode matchingMode,
            long identityRevision,
            String canonicalSenderId,
            IdentityStatus identityStatus,
            List<QqChannelObservationStore.ActiveBindingRef> activeBindings) {

        public ControlSnapshot {
            Objects.requireNonNull(threadId, "threadId");
            threadLabel = threadLabel == null ? "" : threadLabel;
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(channel, "channel");
            accountId = accountId == null ? "" : accountId;
            if (startBoardSeq < 0) {
                throw new IllegalArgumentException("startBoardSeq 不能为负");
            }
            if (bindingGeneration < 1) {
                throw new IllegalArgumentException("bindingGeneration 必须为正");
            }
            bindingEpochId = bindingEpochId == null ? "" : bindingEpochId;
            Objects.requireNonNull(preferredChannelAvailability,
                    "preferredChannelAvailability");
            Objects.requireNonNull(matchingMode, "matchingMode");
            if (identityRevision < 0) {
                throw new IllegalArgumentException("identityRevision 不能为负");
            }
            canonicalSenderId = canonicalSenderId == null ? "" : canonicalSenderId;
            Objects.requireNonNull(identityStatus, "identityStatus");
            activeBindings = activeBindings == null ? List.of() : List.copyOf(activeBindings);
        }
    }
}

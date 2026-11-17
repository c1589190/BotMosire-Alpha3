package io.mosire.bma3.app.channel.matching;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A complete, recomputable snapshot for one logical thread.
 *
 * <p>The caller must pass every observation currently known for the thread and the registry's
 * currently active channel bindings. Each observation must exactly match one binding's channel,
 * account and raw source. Correlation is a pure function of this snapshot: no previous MATCHED
 * result is accepted as input or retained between calls.</p>
 */
public record MatchInput(
        String threadId,
        MatchingMode mode,
        Duration timeTolerance,
        List<ActiveChannelBinding> bindings,
        List<IdentityMapping> identities,
        List<MatchObservation> observations) {

    public MatchInput {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (threadId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("threadId 不能包含控制字符");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(timeTolerance, "timeTolerance");
        if (timeTolerance.isNegative()
                || timeTolerance.compareTo(MatchingLimits.MAX_TIME_TOLERANCE) > 0) {
            throw new IllegalArgumentException("timeTolerance 必须在 0..300 秒内");
        }
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        identities = identities == null ? List.of() : List.copyOf(identities);
        observations = observations == null ? List.of() : List.copyOf(observations);
        if (bindings.size() > MatchingLimits.MAX_BINDINGS) {
            throw new IllegalArgumentException("active bindings 超过上限");
        }
        if (identities.size() > MatchingLimits.MAX_IDENTITIES) {
            throw new IllegalArgumentException("identities 超过上限");
        }
        if (observations.size() > MatchingLimits.MAX_OBSERVATIONS) {
            throw new IllegalArgumentException("observations 超过上限");
        }

        Map<Channel, ActiveChannelBinding> bindingByChannel = new HashMap<>();
        Set<String> boundSources = new HashSet<>();
        for (ActiveChannelBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            if (!threadId.equals(binding.threadId())) {
                throw new IllegalArgumentException("active binding 不属于该 logical thread");
            }
            if (bindingByChannel.putIfAbsent(binding.channel(), binding) != null) {
                throw new IllegalArgumentException("同一 channel 不允许存在多个 active binding");
            }
            if (!boundSources.add(binding.rawSource())) {
                throw new IllegalArgumentException("rawSource 不允许重复绑定");
            }
        }

        Set<String> observationIds = new HashSet<>();
        Set<String> sourceMessageKeys = new HashSet<>();
        for (MatchObservation observation : observations) {
            Objects.requireNonNull(observation, "observation");
            if (!threadId.equals(observation.threadId())) {
                throw new IllegalArgumentException("observation 不属于该 logical thread");
            }
            ActiveChannelBinding binding = bindingByChannel.get(observation.channel());
            if (binding == null
                    || !binding.accountId().equals(observation.accountId())
                    || !binding.rawSource().equals(observation.rawSource())) {
                throw new IllegalArgumentException(
                        "observation 与 active channel binding 的 channel/account/rawSource 不一致");
            }
            if (!observationIds.add(observation.observationId())) {
                throw new IllegalArgumentException("observationId 重复");
            }
            if (!sourceMessageKeys.add(observation.rawSource() + "\u0000"
                    + observation.channelMessageId())) {
                throw new IllegalArgumentException("同一 rawSource 的 channelMessageId 重复");
            }
        }
        for (IdentityMapping identity : identities) {
            Objects.requireNonNull(identity, "identity");
            if (!threadId.equals(identity.threadId())) {
                throw new IllegalArgumentException("identity mapping 不属于该 logical thread");
            }
            if (identity.scopeType() == IdentityMapping.ScopeType.THREAD
                    && !threadId.equals(identity.scopeValue())) {
                throw new IllegalArgumentException("thread scope 必须指向当前 logical thread");
            }
        }
    }

    /**
     * Compatibility constructor. Missing active bindings are supplied as an empty list, so any
     * non-empty observation set fails closed instead of silently bypassing binding validation.
     */
    public MatchInput(String threadId, MatchingMode mode, Duration timeTolerance,
                      List<IdentityMapping> identities, List<MatchObservation> observations) {
        this(threadId, mode, timeTolerance, List.of(), identities, observations);
    }

    /** Safe diagnostic view; never renders observations, bindings or identity mappings. */
    @Override
    public String toString() {
        return "MatchInput[threadId=" + threadId
                + ", mode=" + mode.wireName()
                + ", timeTolerance=" + timeTolerance
                + ", bindings=" + bindings.size()
                + ", identities=" + identities.size()
                + ", observations=" + observations.size() + "]";
    }
}

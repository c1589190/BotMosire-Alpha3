package io.mosire.bma3.app.channel.matching;

import java.util.Objects;

/**
 * One explicitly injected sender-identity mapping.
 *
 * <p>Strict matching requires a valid mapping for both observations and the same canonical user.
 * A mapping is limited either to the whole logical thread or to one exact raw source. The matcher
 * never infers that an OneBot QQ number equals an official openid.</p>
 */
public record IdentityMapping(
        String threadId,
        Channel channel,
        String channelSenderId,
        String canonicalSenderId,
        ScopeType scopeType,
        String scopeValue) {

    public enum ScopeType {
        THREAD,
        SOURCE
    }

    public IdentityMapping {
        threadId = requireNonBlank(threadId, "threadId");
        Objects.requireNonNull(channel, "channel");
        channelSenderId = requireNonBlank(channelSenderId, "channelSenderId");
        canonicalSenderId = requireNonBlank(canonicalSenderId, "canonicalSenderId");
        Objects.requireNonNull(scopeType, "scopeType");
        scopeValue = requireNonBlank(scopeValue, "scopeValue");
        if (containsControl(threadId) || containsControl(channelSenderId)
                || containsControl(canonicalSenderId) || containsControl(scopeValue)) {
            throw new IllegalArgumentException("identity mapping 不能包含控制字符");
        }
    }

    /** True when this mapping is explicitly applicable to the observation. */
    public boolean appliesTo(MatchObservation observation) {
        if (!threadId.equals(observation.threadId()) || channel != observation.channel()
                || !channelSenderId.equals(observation.senderId())) {
            return false;
        }
        return switch (scopeType) {
            case THREAD -> scopeValue.equals(observation.threadId());
            case SOURCE -> scopeValue.equals(observation.rawSource());
        };
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}

package io.github.c1589190.bma3.app.channel.matching;

import java.util.Objects;

/**
 * One currently active raw-source binding injected from the persisted registry.
 *
 * <p>The matcher does not read the registry. The caller supplies the active bindings for the
 * logical thread, and every observation must exactly match one binding on channel, account and
 * raw source. A logical thread may have at most one active source per channel.</p>
 */
public record ActiveChannelBinding(
        String threadId,
        Channel channel,
        String accountId,
        String rawSource) {

    public ActiveChannelBinding {
        threadId = requireNonBlank(threadId, "threadId");
        Objects.requireNonNull(channel, "channel");
        accountId = requireNonBlank(accountId, "accountId");
        rawSource = requireNonBlank(rawSource, "rawSource");
        if (containsControl(threadId) || containsControl(accountId)
                || containsControl(rawSource)) {
            throw new IllegalArgumentException("active binding 不能包含控制字符");
        }
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

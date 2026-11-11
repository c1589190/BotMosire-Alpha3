package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.priority.SourceKeyer;
import io.mosire.bma3.core.source.SourceAddress;

/**
 * Projects a QQ board address onto the two-level configuration key.
 *
 * <p>{@code qq_group/<accountId>/<groupId>} becomes {@code qq_group/<groupId>} and
 * {@code qq_private/<accountId>/<peerQq>} becomes {@code qq_private/<peerQq>}: the bot account is
 * routing detail, not something a user or an agent configures.</p>
 */
public final class QqSourceKeyer implements SourceKeyer {

    @Override
    public String keyFor(SourceAddress source) {
        String platform = source.platform();
        if ("qq_group".equals(platform) || "qq_private".equals(platform)) {
            String target = source.lastSegment();
            return target.isBlank() ? platform : platform + "/" + target;
        }
        String target = source.lastSegment();
        return target.isBlank() ? platform : platform + "/" + target;
    }
}

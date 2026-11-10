package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabeler;
import java.util.Set;

/** Human-readable labels for the QQ source platforms; the Inbox never sees this switch. */
public final class QqSourceLabeler implements SourceLabeler {

    private static final Set<String> PLATFORMS = Set.of("qq_group", "qq_private", "qq_system");

    @Override
    public boolean supports(String platform) {
        return PLATFORMS.contains(platform);
    }

    @Override
    public String label(SourceAddress source) {
        String id = source.lastSegment();
        return switch (source.platform()) {
            case "qq_group" -> "QQ群 " + id;
            case "qq_private" -> "QQ私聊 " + id;
            case "qq_system" -> "QQ系统 " + id;
            default -> source.displayFallback();
        };
    }
}

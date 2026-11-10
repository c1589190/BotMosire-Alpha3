package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.priority.PrioritySignals;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;

/** QQ-specific signal extraction; the generic resolver only sees PrioritySignals. */
public final class QqSignalExtractor {

    private QqSignalExtractor() {
    }

    public static PrioritySignals signals(OneBotEvent event) {
        String self = Long.toString(event.selfId());
        boolean mention = event.segments().stream()
                .anyMatch(segment -> "at".equals(segment.type())
                        && (self.equals(segment.text()) || "all".equals(segment.text())));
        String text = event.text() == null ? "" : event.text().trim();
        boolean command = text.startsWith("/") || text.startsWith("!");
        // reply/name need a persisted outbound-message index / bot nickname; not available yet.
        return new PrioritySignals(mention, false, false, command);
    }
}

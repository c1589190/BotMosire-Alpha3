package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.priority.PriorityAttributes;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * QQ-specific attribute extraction. The generic resolver only sees opaque attribute keys and
 * their configured deltas (see {@link PriorityAttributes}).
 *
 * <p>Every attribute here answers "is this actually aimed at the bot?" — an {@code @all} is not,
 * an {@code @someone-else} is not, and a {@code /command} typed into a group without at-ing the
 * bot is not. Whether such an attribute is worth any points is a configuration question, not a
 * code question; {@code weighting.attributes} decides.</p>
 */
public final class QqSignalExtractor {

    public static final String AT_BOT = "qq_at_bot";
    public static final String AT_ALL = "qq_at_all";
    public static final String AT_OTHER = "qq_at_other";
    public static final String REPLY_TO_BOT = "qq_reply_to_bot";
    public static final String SELF_NAME = "qq_self_name";
    public static final String COMMAND = "qq_command";

    private QqSignalExtractor() {
    }

    public static PriorityAttributes attributes(OneBotEvent event, String selfName,
                                                boolean replyToBot) {
        Set<String> keys = new LinkedHashSet<>();
        String self = event.selfId() > 0L ? Long.toString(event.selfId()) : "";
        boolean atBot = false;
        boolean atOther = false;
        for (var segment : event.segments()) {
            if (!"at".equals(segment.type())) {
                continue;
            }
            String target = segment.data("qq");
            if ("all".equalsIgnoreCase(target)) {
                keys.add(AT_ALL);
            } else if (!self.isBlank() && self.equals(target)) {
                atBot = true;
            } else if (!target.isBlank()) {
                atOther = true;
            }
        }
        if (atBot) {
            keys.add(AT_BOT);
        }
        if (atOther) {
            keys.add(AT_OTHER);
        }
        if (replyToBot) {
            keys.add(REPLY_TO_BOT);
        }
        String text = event.text() == null ? "" : event.text().trim();
        if (mentionsSelfName(text, selfName)) {
            keys.add(SELF_NAME);
        }
        boolean directed = event.groupId() <= 0L || atBot;
        if (directed && (text.startsWith("/") || text.startsWith("!"))) {
            keys.add(COMMAND);
        }
        return new PriorityAttributes(keys);
    }

    /** Substring match on the bot's own nickname; blank names never match. */
    static boolean mentionsSelfName(String text, String selfName) {
        if (text == null || text.isBlank() || selfName == null || selfName.isBlank()) {
            return false;
        }
        return text.toLowerCase(java.util.Locale.ROOT)
                .contains(selfName.toLowerCase(java.util.Locale.ROOT));
    }
}

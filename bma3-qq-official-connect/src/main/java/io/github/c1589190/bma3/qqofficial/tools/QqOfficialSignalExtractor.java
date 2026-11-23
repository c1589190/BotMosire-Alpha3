package io.github.c1589190.bma3.qqofficial.tools;

import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.priority.PriorityAttributes;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts adapter-neutral priority signals from an official QQ message.
 *
 * <p>Attribute keys intentionally match the OneBot adapter ({@code qq_at_bot},
 * {@code qq_at_all}, {@code qq_at_other}, {@code qq_reply_to_bot}, {@code qq_self_name},
 * {@code qq_command}), so the global {@code weighting.attributes} defaults work unchanged for both
 * channels. The extractor owns no logging and never receives/echoes credentials or message bodies
 * outside the supplied event.</p>
 */
public final class QqOfficialSignalExtractor {

    public static final String AT_BOT = "qq_at_bot";
    public static final String AT_ALL = "qq_at_all";
    public static final String AT_OTHER = "qq_at_other";
    public static final String REPLY_TO_BOT = "qq_reply_to_bot";
    public static final String SELF_NAME = "qq_self_name";
    public static final String COMMAND = "qq_command";

    public static final List<String> DEFAULT_COMMAND_PREFIXES = List.of("/", "!");

    private QqOfficialSignalExtractor() {
    }

    public static PriorityAttributes attributes(OfficialQqEvent event, String selfName,
                                                boolean replyToBot) {
        return attributes(event, selfName, replyToBot, DEFAULT_COMMAND_PREFIXES);
    }

    /**
     * Same signals as the existing OneBot extractor: mention flags,
     * explicit reply-to-bot evidence, a case-insensitive self-name substring and command prefixes
     * only when the message is directed at the bot. A group-wide message without an @bot is not a
     * command even when its text starts with a command prefix.
     */
    public static PriorityAttributes attributes(OfficialQqEvent event, String selfName,
                                                boolean replyToBot,
                                                List<String> commandPrefixes) {
        Objects.requireNonNull(event, "event");
        Set<String> keys = new LinkedHashSet<>();
        boolean atBot = event.atBot();
        boolean atAll = event.atAll();
        boolean atOther = false;

        for (Segment segment : event.segments()) {
            if (!"at".equals(segment.type())) {
                continue;
            }
            String scope = segment.data("scope");
            String openid = segment.data("openid");
            String name = segment.data("name");
            boolean mentionAll = "all".equalsIgnoreCase(scope);
            boolean mentionBot = "true".equalsIgnoreCase(segment.data("is_you"));
            if (mentionAll) {
                atAll = true;
            } else if (mentionBot) {
                atBot = true;
            } else if (!openid.isBlank() || !name.isBlank()) {
                atOther = true;
            }
        }

        if (atBot) {
            keys.add(AT_BOT);
        }
        if (atAll) {
            keys.add(AT_ALL);
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

        boolean directed = !event.groupMessage() || atBot;
        List<String> prefixes = commandPrefixes == null || commandPrefixes.isEmpty()
                ? DEFAULT_COMMAND_PREFIXES : commandPrefixes;
        if (directed && startsWithConfiguredPrefix(text, prefixes)) {
            keys.add(COMMAND);
        }
        return new PriorityAttributes(keys);
    }

    /** Convenience overload when the caller has no configured bot nickname for this event. */
    public static PriorityAttributes attributes(OfficialQqEvent event, boolean replyToBot) {
        return attributes(event, "", replyToBot);
    }

    /** Substring match on the bot's own nickname; blank names never match. */
    static boolean mentionsSelfName(String text, String selfName) {
        if (text == null || text.isBlank() || selfName == null || selfName.isBlank()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(selfName.toLowerCase(Locale.ROOT));
    }

    private static boolean startsWithConfiguredPrefix(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

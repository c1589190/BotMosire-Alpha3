package io.mosire.bma3.app.channel.matching;

import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.Segment;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable observation offered to the pure matcher.
 *
 * <p>{@code segments} is used only for text/media projection. Only media MIME metadata is read
 * from a segment's data map; URL, query, rkey, filename and all other metadata are ignored by the
 * matching criteria. The observation itself is never returned or rendered by
 * {@link CrossChannelMatcher}.</p>
 *
 * <p>{@code contentIntegrity}, {@code timeReliability} and
 * {@code structuredMentionsTrusted} are explicit evidence flags. Compatibility constructors
 * default them to fail-closed values.</p>
 */
public record MatchObservation(
        String observationId,
        String threadId,
        Channel channel,
        String accountId,
        String rawSource,
        String channelMessageId,
        String senderId,
        Direction direction,
        Instant messageAt,
        ContentIntegrity contentIntegrity,
        TimeReliability timeReliability,
        boolean structuredMentionsTrusted,
        List<Segment> segments) {

    public MatchObservation {
        observationId = requireNonBlank(observationId, "observationId");
        threadId = requireNonBlank(threadId, "threadId");
        Objects.requireNonNull(channel, "channel");
        accountId = accountId == null ? "" : accountId;
        rawSource = requireNonBlank(rawSource, "rawSource");
        channelMessageId = requireNonBlank(channelMessageId, "channelMessageId");
        senderId = requireNonBlank(senderId, "senderId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(messageAt, "messageAt");
        Objects.requireNonNull(contentIntegrity, "contentIntegrity");
        Objects.requireNonNull(timeReliability, "timeReliability");
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (segments.size() > MatchingLimits.MAX_SEGMENTS_PER_OBSERVATION) {
            throw new IllegalArgumentException("单条消息 segments 超过上限");
        }
        int textCodePoints = 0;
        for (Segment segment : segments) {
            if (segment == null) {
                continue;
            }
            if ("text".equalsIgnoreCase(segment.type())) {
                textCodePoints += segment.text().codePointCount(0, segment.text().length());
                if (textCodePoints > MatchingLimits.MAX_TEXT_CODE_POINTS_PER_OBSERVATION) {
                    throw new IllegalArgumentException("单条消息正文超过上限");
                }
            }
        }
        if (containsControl(observationId) || containsControl(threadId)
                || (!accountId.isEmpty() && containsControl(accountId))
                || containsControl(rawSource)
                || containsControl(channelMessageId) || containsControl(senderId)) {
            throw new IllegalArgumentException("observation 标识不能包含控制字符");
        }
    }

    /**
     * Compatibility constructor. It deliberately marks content and time as unknown and structured
     * mention handling as untrusted, so the observation can never produce a positive match.
     */
    public MatchObservation(String observationId, String threadId, Channel channel,
                            String rawSource, String channelMessageId, String senderId,
                            Direction direction, Instant messageAt,
                            List<Segment> segments) {
        this(observationId, threadId, channel, "", rawSource, channelMessageId, senderId,
                direction, messageAt, ContentIntegrity.UNKNOWN, TimeReliability.UNKNOWN,
                false, segments);
    }

    /**
     * Compatibility text-only constructor with fail-closed evidence defaults.
     */
    public MatchObservation(String observationId, String threadId, Channel channel,
                            String rawSource, String channelMessageId, String senderId,
                            Direction direction, Instant messageAt, String text) {
        this(observationId, threadId, channel, "", rawSource, channelMessageId, senderId,
                direction, messageAt, ContentIntegrity.UNKNOWN, TimeReliability.UNKNOWN,
                false, List.of(new Segment("text", text)));
    }

    /** Safe diagnostic form; sender, raw source, text and segment metadata are omitted. */
    @Override
    public String toString() {
        return "MatchObservation[observationId=" + observationId
                + ", threadId=" + threadId
                + ", channel=" + channel.wireName()
                + ", channelMessageId=" + channelMessageId
                + ", direction=" + direction
                + ", messageAt=" + messageAt
                + ", contentIntegrity=" + contentIntegrity
                + ", timeReliability=" + timeReliability
                + ", structuredMentionsTrusted=" + structuredMentionsTrusted
                + ", segments=" + segments.size() + "]";
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

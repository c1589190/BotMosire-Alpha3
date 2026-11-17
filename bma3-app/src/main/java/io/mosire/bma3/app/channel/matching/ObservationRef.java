package io.mosire.bma3.app.channel.matching;

import java.time.Instant;
import java.util.Objects;

/**
 * Safe output reference for one observation.
 *
 * <p>Only opaque IDs, the exact persisted source/account, the message ID and the source timestamp
 * are exposed. No body text, URL, metadata, credential, salted/unsalted content digest or other
 * body-derived summary is returned.</p>
 */
public record ObservationRef(
        String observationId,
        String channel,
        String accountId,
        String rawSource,
        String channelMessageId,
        Instant messageAt) {

    public ObservationRef {
        observationId = requireNonBlank(observationId, "observationId");
        channel = requireNonBlank(channel, "channel");
        accountId = requireNonBlank(accountId, "accountId");
        rawSource = requireNonBlank(rawSource, "rawSource");
        channelMessageId = requireNonBlank(channelMessageId, "channelMessageId");
        Objects.requireNonNull(messageAt, "messageAt");
    }

    public static ObservationRef of(MatchObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return new ObservationRef(observation.observationId(), observation.channel().wireName(),
                observation.accountId(), observation.rawSource(), observation.channelMessageId(),
                observation.messageAt());
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}

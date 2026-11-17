package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Conservative late-duplicate detector used only when a fallback display channel is about to open a
 * new window. It never emits or records MATCHED.
 *
 * <p>A candidate requires the opposite channel, same thread/direction, a trustworthy or
 * conservatively bounded time proximity, and content that is exactly equal or cannot be compared.
 * Two explicitly mapped, different canonical senders disprove candidacy; an unknown sender never
 * does. This detector can suppress a new wake even in {@code matching=off}, but never upgrades
 * evidence to a match claim.</p>
 */
public final class FallbackSuppression {

    private static final Map<String, Integer> NO_MEDIA = Map.of();

    public record Candidate(Observation observation, String itemId) {
        public Candidate {
            Objects.requireNonNull(observation, "observation");
            itemId = itemId == null ? "" : itemId;
        }
    }

    public List<Candidate> findCandidates(
            Observation incoming,
            List<Observation> observations,
            Duration tolerance,
            Function<Observation, Optional<Message>> boardLookup) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(tolerance, "tolerance");
        Objects.requireNonNull(boardLookup, "boardLookup");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance 不能为负");
        }
        Optional<Message> incomingMessage = boardLookup.apply(incoming);
        if (incomingMessage.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Observation historical : observations) {
            if (historical.observationId().equals(incoming.observationId())
                    || historical.threadId().equals(incoming.threadId()) == false
                    || historical.channel() == incoming.channel()
                    || historical.direction() != incoming.direction()
                    || historical.routeState() != RouteState.DISPLAY_LINKED
                    || historical.itemId().isEmpty()
                    || definitelyDifferentMappedSenders(incoming, historical)
                    || !withinWindow(incoming, historical, tolerance)) {
                continue;
            }
            Optional<Message> historicalMessage = boardLookup.apply(historical);
            if (historicalMessage.isEmpty()) {
                continue;
            }
            if (candidateContent(incoming, historical,
                    incomingMessage.orElseThrow(), historicalMessage.orElseThrow())) {
                candidates.add(new Candidate(historical, historical.itemId().orElseThrow()));
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean definitelyDifferentMappedSenders(Observation first,
                                                            Observation second) {
        // Empty/ambiguous identity cannot prove that a late observation is a new message. Only
        // two explicit canonical mappings can exclude a content/time candidate.
        if (first.identityStatus() != IdentityStatus.MAPPED
                || second.identityStatus() != IdentityStatus.MAPPED) {
            return false;
        }
        Optional<String> firstId = first.canonicalSenderId().filter(id -> !id.isBlank());
        Optional<String> secondId = second.canonicalSenderId().filter(id -> !id.isBlank());
        return firstId.isPresent() && secondId.isPresent()
                && !firstId.orElseThrow().equals(secondId.orElseThrow());
    }

    static boolean withinWindow(Observation first, Observation second, Duration tolerance) {
        if (first.timeReliability() == TimeReliability.RELIABLE
                && second.timeReliability() == TimeReliability.RELIABLE) {
            return distance(first.messageAt(), second.messageAt()).compareTo(tolerance) <= 0;
        }
        // Missing/fallback platform time cannot prove same-message identity. Use receive time only
        // as a conservative suppression window; this can over-suppress but never over-wakes.
        return distance(first.observedAt(), second.observedAt()).compareTo(tolerance) <= 0;
    }

    private static Duration distance(Instant first, Instant second) {
        Duration delta = Duration.between(first, second);
        return delta.isNegative() ? delta.negated() : delta;
    }

    private static boolean candidateContent(Observation incoming, Observation historical,
                                            Message incomingMessage, Message historicalMessage) {
        if (incoming.contentIntegrity() != ContentIntegrity.COMPLETE
                || historical.contentIntegrity() != ContentIntegrity.COMPLETE) {
            return true;
        }
        Projection first = project(incomingMessage.inbound().segments());
        Projection second = project(historicalMessage.inbound().segments());
        if (!first.comparable() || !second.comparable()) {
            return true;
        }
        return first.text().equals(second.text()) && first.media().equals(second.media());
    }

    private static Projection project(List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        SortedMap<String, Integer> media = new TreeMap<>();
        boolean comparable = true;
        for (Segment segment : segments) {
            if (segment == null) {
                comparable = false;
                continue;
            }
            switch (segment.type()) {
                case "text" -> text.append(segment.text()).append(' ');
                case "at", "mention" -> {
                    // Structured at wrappers are display decoration, not body evidence.
                }
                case "image", "video", "record", "file" -> {
                    String mime = mediaMime(segment.data());
                    if (mime == null) {
                        comparable = false;
                    }
                    media.merge(segment.type() + "|" + (mime == null ? "" : mime),
                            1, Integer::sum);
                }
                default -> comparable = false;
            }
        }
        return new Projection(normalizeText(text.toString()),
                media.isEmpty() ? NO_MEDIA : Map.copyOf(media), comparable);
    }

    private static String mediaMime(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String found = null;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase();
            if (!key.equals("mime") && !key.equals("mime_type") && !key.equals("mimetype")
                    && !key.equals("content_type") && !key.equals("contenttype")
                    && !key.equals("media_type") && !key.equals("mediatype")) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip().toLowerCase();
            if (value.isBlank()) {
                return null;
            }
            if (found != null && !found.equals(value)) {
                return null;
            }
            found = value;
        }
        return found;
    }

    private static String normalizeText(String raw) {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    private record Projection(String text, Map<String, Integer> media, boolean comparable) {
    }
}

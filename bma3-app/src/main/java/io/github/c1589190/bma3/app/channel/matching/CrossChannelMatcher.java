package io.github.c1589190.bma3.app.channel.matching;

import io.github.c1589190.bma3.core.message.Segment;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic, side-effect-free 1:1 cross-channel correlation kernel.
 *
 * <p>The matcher is a pure function of one {@link MatchInput} snapshot. It never reads a database,
 * writes evidence, creates Inbox items, schedules wakes, performs I/O or uses timestamps from a
 * clock. Callers must supply the complete observation set for the logical thread and the currently
 * active channel bindings, then pass the whole set again whenever evidence changes. No prior result
 * is retained. This is deliberate: if a late duplicate makes an earlier local match ambiguous, a
 * full recomputation must demote the affected observations to
 * {@link ObservationState#UNVERIFIED} rather than preserve stale certainty.</p>
 *
 * <p>Comparison rules:</p>
 * <ul>
 *   <li>only opposite channels in the same logical thread and direction are candidates;</li>
 *   <li>each observation must match an injected active binding's channel, account and raw source;</li>
 *   <li>time distance must be trusted and at most the injected 0..300 second tolerance,
 *       inclusively;</li>
 *   <li>{@link MatchingMode#STRICT} requires an explicit mapping on both sides to one canonical
 *       sender. Raw sender equality alone is never considered proof;</li>
 *   <li>content completeness, timestamp reliability and structured-mention trust must be explicit;
 *       incomplete/unknown/truncated evidence fails closed;</li>
 *   <li>text is NFKC-normalized, trimmed and whitespace-collapsed. Literal text is never rewritten:
 *       CQ markers and arbitrary {@code @...} prefixes are not stripped. Only a trusted structured
 *       adapter {@code at}/{@code mention} segment may be ignored as a wrapper;</li>
 *   <li>media is compared by type, count and MIME. URL, query, rkey, filename and other metadata
 *       are never compared; missing or ambiguous MIME is treated as incomplete evidence;</li>
 *   <li>card/rich messages and unreliable quote/forward/reference segments fail closed as
 *       incomparable;</li>
 *   <li>more than one candidate, or any unresolved temporal peer blocking a nominal pair, yields
 *       {@code UNVERIFIED};</li>
 *   <li>{@link MatchingMode#LOOSE} can only emit {@code UNVERIFIED_MATCH}, while
 *       {@link MatchingMode#OFF} never emits a pair.</li>
 * </ul>
 *
 * <p>All returned objects contain IDs, source/account, timestamps, states and counts only. Input
 * segments are never retained in a result, and no body-derived hash is emitted.</p>
 */
public final class CrossChannelMatcher {

    private static final Set<String> MEDIA_TYPES = Set.of("image", "video", "record", "file");
    private static final Set<String> MIME_KEYS = Set.of(
            "mime", "mime_type", "mimetype", "content_type", "contenttype",
            "media_type", "mediatype");
    private static final int MAX_MIME_CODE_POINTS = 256;
    private static final Comparator<ObservationRef> REF_ORDER =
            Comparator.comparing(ObservationRef::channel)
                    .thenComparing(ObservationRef::messageAt)
                    .thenComparing(ObservationRef::observationId)
                    .thenComparing(ObservationRef::channelMessageId);
    private static final Comparator<MatchObservation> OBSERVATION_ORDER =
            Comparator.comparing(MatchObservation::channel)
                    .thenComparing(MatchObservation::messageAt)
                    .thenComparing(MatchObservation::observationId)
                    .thenComparing(MatchObservation::channelMessageId);

    /**
     * Recomputes correlation from the complete snapshot.
     *
     * @throws IllegalArgumentException if the snapshot is malformed or exceeds resource limits
     */
    public MatchResult recompute(MatchInput input) {
        Objects.requireNonNull(input, "input");
        List<MatchObservation> ordered = new ArrayList<>(input.observations());
        ordered.sort(OBSERVATION_ORDER);
        List<Projection> projections = projectAll(ordered);
        List<IdentityResolution> identities = resolveIdentities(input, ordered);

        if (input.mode() == MatchingMode.OFF || ordered.isEmpty()) {
            return finish(input, ordered);
        }

        PeerGraph peers = buildPeerGraph(ordered, input.timeTolerance(), input.mode(), identities);
        ObservationState[] states = new ObservationState[ordered.size()];
        Integer[] peersByIndex = new Integer[ordered.size()];
        List<PairDecision> pairDecisions = new ArrayList<>();

        for (int index = 0; index < ordered.size(); index++) {
            if (states[index] != null) {
                continue;
            }
            int candidateCount = peers.candidateCount()[index];
            if (candidateCount > 1) {
                states[index] = ObservationState.UNVERIFIED;
                continue;
            }
            if (candidateCount == 0) {
                states[index] = peers.unresolvedPeer()[index]
                        || !projections.get(index).reliable()
                        ? ObservationState.UNVERIFIED
                        : ObservationState.CHANNEL_ONLY;
                continue;
            }

            int peerIndex = peers.firstCandidate()[index];
            if (peers.candidateCount()[peerIndex] != 1
                    || peers.firstCandidate()[peerIndex] != index) {
                states[index] = ObservationState.UNVERIFIED;
                continue;
            }
            // A nominal mutual pair is still not unique if either endpoint has another
            // same-window peer whose identity/comparability cannot be resolved. Fail closed.
            if (peers.unresolvedPeer()[index] || peers.unresolvedPeer()[peerIndex]) {
                states[index] = ObservationState.UNVERIFIED;
                states[peerIndex] = ObservationState.UNVERIFIED;
                continue;
            }

            ContentRelation relation = relation(projections.get(index), projections.get(peerIndex));
            ObservationState pairState = pairState(relation, input.mode(),
                    identities.get(index), identities.get(peerIndex));
            states[index] = pairState;
            states[peerIndex] = pairState;
            if (pairState == ObservationState.MATCHED
                    || pairState == ObservationState.UNVERIFIED_MATCH
                    || pairState == ObservationState.CONFLICT) {
                peersByIndex[index] = peerIndex;
                peersByIndex[peerIndex] = index;
                pairDecisions.add(new PairDecision(index, peerIndex, pairState));
            }
        }

        for (int index = 0; index < ordered.size(); index++) {
            if (states[index] == null) {
                states[index] = ObservationState.UNVERIFIED;
            }
        }
        return result(input, ordered, states, peersByIndex, pairDecisions);
    }

    /** Alias with a vocabulary matching the persistence layer's "match" action. */
    public MatchResult match(MatchInput input) {
        return recompute(input);
    }

    private static List<Projection> projectAll(List<MatchObservation> observations) {
        List<Projection> projections = new ArrayList<>(observations.size());
        for (MatchObservation observation : observations) {
            projections.add(project(observation));
        }
        return List.copyOf(projections);
    }

    private static Projection project(MatchObservation observation) {
        StringBuilder rawText = new StringBuilder();
        SortedMap<String, Integer> media = new TreeMap<>();
        boolean reliable = observation.contentIntegrity().reliable();
        boolean hasStructuredMention = false;

        for (Segment segment : observation.segments()) {
            if (segment == null) {
                reliable = false;
                continue;
            }
            String type = segment.type().strip().toLowerCase(Locale.ROOT);
            switch (type) {
                case "text" -> rawText.append(segment.text()).append(' ');
                case "at", "mention" -> {
                    if (observation.structuredMentionsTrusted()) {
                        hasStructuredMention = true;
                    } else {
                        reliable = false;
                    }
                }
                case "image", "video", "record", "file" -> {
                    String mime = mediaMime(segment.data());
                    if (mime == null) {
                        reliable = false;
                    }
                    media.merge(type + "|" + (mime == null ? "" : mime), 1, Integer::sum);
                }
                default -> reliable = false;
            }
        }

        String text = normalizeText(rawText.toString());
        boolean mentionOnly = hasStructuredMention && text.isEmpty() && media.isEmpty();
        if (mentionOnly) {
            // An at-only wrapper is not evidence of an empty logical message. It cannot be used to
            // collapse "mention-only" and truly empty content into the same positive match.
            reliable = false;
        }
        return new Projection(text, media, reliable);
    }

    private static String normalizeText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = collapsed.length() > 0;
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.appendCodePoint(codePoint);
        }
        return collapsed.toString();
    }

    /**
     * Reads only an explicit MIME field. Missing, invalid, conflicting duplicate MIME fields or any
     * other metadata cannot be used as positive evidence.
     */
    private static String mediaMime(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String found = null;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (entry.getKey() == null
                    || !MIME_KEYS.contains(entry.getKey().strip().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String candidate = normalizeMime(entry.getValue());
            if (candidate == null) {
                return null;
            }
            if (found != null && !found.equals(candidate)) {
                return null;
            }
            found = candidate;
        }
        return found;
    }

    private static String normalizeMime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.codePointCount(0, normalized.length()) > MAX_MIME_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }

    private static ContentRelation relation(Projection first, Projection second) {
        if (!first.reliable() || !second.reliable()) {
            return ContentRelation.INCOMPLETE;
        }
        if (first.text().equals(second.text()) && first.media().equals(second.media())) {
            return ContentRelation.EQUAL;
        }
        return ContentRelation.DIFFERENT;
    }

    private static ObservationState pairState(ContentRelation relation, MatchingMode mode,
                                              IdentityResolution first,
                                              IdentityResolution second) {
        if (relation == ContentRelation.INCOMPLETE) {
            return ObservationState.UNVERIFIED;
        }
        if (relation == ContentRelation.DIFFERENT) {
            if (mode == MatchingMode.STRICT
                    || (first.state() == IdentityState.UNIQUE
                    && second.state() == IdentityState.UNIQUE
                    && first.canonicalUserId().equals(second.canonicalUserId()))) {
                return ObservationState.CONFLICT;
            }
            return ObservationState.UNVERIFIED;
        }
        return mode == MatchingMode.STRICT
                ? ObservationState.MATCHED
                : ObservationState.UNVERIFIED_MATCH;
    }

    private static PeerGraph buildPeerGraph(List<MatchObservation> observations,
                                            Duration tolerance,
                                            MatchingMode mode,
                                            List<IdentityResolution> identities) {
        int[] candidateCount = new int[observations.size()];
        int[] firstCandidate = new int[observations.size()];
        java.util.Arrays.fill(firstCandidate, -1);
        boolean[] unresolvedPeer = new boolean[observations.size()];

        for (int first = 0; first < observations.size(); first++) {
            MatchObservation left = observations.get(first);
            for (int second = first + 1; second < observations.size(); second++) {
                MatchObservation right = observations.get(second);
                if (left.channel() == right.channel()
                        || left.direction() != right.direction()) {
                    continue;
                }

                IdentityResolution leftIdentity = identities.get(first);
                IdentityResolution rightIdentity = identities.get(second);
                boolean sameIdentity = leftIdentity.state() == IdentityState.UNIQUE
                        && rightIdentity.state() == IdentityState.UNIQUE
                        && leftIdentity.canonicalUserId().equals(rightIdentity.canonicalUserId());
                boolean knownDifferent = leftIdentity.state() == IdentityState.UNIQUE
                        && rightIdentity.state() == IdentityState.UNIQUE
                        && !leftIdentity.canonicalUserId().equals(rightIdentity.canonicalUserId());
                if (knownDifferent) {
                    continue;
                }

                if (!left.timeReliability().reliable() || !right.timeReliability().reliable()) {
                    unresolvedPeer[first] = true;
                    unresolvedPeer[second] = true;
                    continue;
                }
                if (!withinTolerance(left.messageAt(), right.messageAt(), tolerance)) {
                    continue;
                }

                boolean ambiguousIdentity = leftIdentity.state() == IdentityState.AMBIGUOUS
                        || rightIdentity.state() == IdentityState.AMBIGUOUS;
                boolean candidate = mode == MatchingMode.STRICT
                        ? sameIdentity
                        : !ambiguousIdentity;
                boolean unresolved = mode == MatchingMode.STRICT
                        ? !sameIdentity
                        : ambiguousIdentity;
                if (candidate) {
                    candidateCount[first]++;
                    candidateCount[second]++;
                    if (firstCandidate[first] < 0) {
                        firstCandidate[first] = second;
                    }
                    if (firstCandidate[second] < 0) {
                        firstCandidate[second] = first;
                    }
                }
                if (unresolved) {
                    unresolvedPeer[first] = true;
                    unresolvedPeer[second] = true;
                }
            }
        }
        return new PeerGraph(candidateCount, firstCandidate, unresolvedPeer);
    }

    private static boolean withinTolerance(Instant first, Instant second, Duration tolerance) {
        Duration delta = Duration.between(first, second);
        if (delta.isNegative()) {
            delta = delta.negated();
        }
        return delta.compareTo(tolerance) <= 0;
    }

    private static List<IdentityResolution> resolveIdentities(MatchInput input,
                                                              List<MatchObservation> observations) {
        List<IdentityResolution> resolutions = new ArrayList<>(observations.size());
        for (MatchObservation observation : observations) {
            TreeSet<String> canonicals = new TreeSet<>();
            for (IdentityMapping mapping : input.identities()) {
                if (mapping.appliesTo(observation)) {
                    canonicals.add(mapping.canonicalSenderId());
                }
            }
            if (canonicals.isEmpty()) {
                resolutions.add(new IdentityResolution(IdentityState.ABSENT, ""));
            } else if (canonicals.size() == 1) {
                resolutions.add(new IdentityResolution(IdentityState.UNIQUE, canonicals.first()));
            } else {
                resolutions.add(new IdentityResolution(IdentityState.AMBIGUOUS, ""));
            }
        }
        return List.copyOf(resolutions);
    }

    private static MatchResult finish(MatchInput input, List<MatchObservation> observations) {
        ObservationState[] states = new ObservationState[observations.size()];
        for (int index = 0; index < states.length; index++) {
            states[index] = ObservationState.CHANNEL_ONLY;
        }
        return result(input, observations, states, new Integer[observations.size()], List.of());
    }

    private static MatchResult result(MatchInput input,
                                      List<MatchObservation> observations,
                                      ObservationState[] states,
                                      Integer[] peersByIndex,
                                      List<PairDecision> pairDecisions) {
        List<ObservationRef> refs = new ArrayList<>(observations.size());
        for (MatchObservation observation : observations) {
            refs.add(ObservationRef.of(observation));
        }

        List<ObservationResult> results = new ArrayList<>(observations.size());
        for (int index = 0; index < observations.size(); index++) {
            Integer peerIndex = peersByIndex[index];
            Optional<ObservationRef> peer = peerIndex == null
                    ? Optional.empty()
                    : Optional.of(refs.get(peerIndex));
            results.add(new ObservationResult(refs.get(index), states[index], peer));
        }
        results.sort(Comparator.comparing(ObservationResult::observation, REF_ORDER));

        List<MatchPair> pairs = new ArrayList<>(pairDecisions.size());
        for (PairDecision decision : pairDecisions) {
            pairs.add(new MatchPair(decision.state(), refs.get(decision.first()),
                    refs.get(decision.second())));
        }
        pairs.sort(Comparator.comparing(MatchPair::first, REF_ORDER)
                .thenComparing(MatchPair::second, REF_ORDER));

        MatchCounts counts = counts(results, pairs, observations.size());
        return new MatchResult(input.threadId(), input.mode(),
                List.copyOf(results), List.copyOf(pairs), counts);
    }

    private static MatchCounts counts(List<ObservationResult> results, List<MatchPair> pairs,
                                      int observations) {
        int matched = 0;
        int unverifiedMatch = 0;
        int unverified = 0;
        int conflict = 0;
        int channelOnly = 0;
        for (ObservationResult result : results) {
            switch (result.state()) {
                case MATCHED -> matched++;
                case UNVERIFIED_MATCH -> unverifiedMatch++;
                case UNVERIFIED -> unverified++;
                case CONFLICT -> conflict++;
                case CHANNEL_ONLY -> channelOnly++;
            }
        }
        int matchedPairs = 0;
        int unverifiedMatchPairs = 0;
        int conflictPairs = 0;
        for (MatchPair pair : pairs) {
            switch (pair.state()) {
                case MATCHED -> matchedPairs++;
                case UNVERIFIED_MATCH -> unverifiedMatchPairs++;
                case CONFLICT -> conflictPairs++;
                default -> throw new IllegalStateException("unexpected pair state");
            }
        }
        return new MatchCounts(observations, matched, unverifiedMatch, unverified, conflict,
                channelOnly, matchedPairs, unverifiedMatchPairs, conflictPairs);
    }

    private enum ContentRelation {
        EQUAL,
        DIFFERENT,
        INCOMPLETE
    }

    private enum IdentityState {
        ABSENT,
        UNIQUE,
        AMBIGUOUS
    }

    private record Projection(String text, SortedMap<String, Integer> media, boolean reliable) {
    }

    private record IdentityResolution(IdentityState state, String canonicalUserId) {
    }

    private record PeerGraph(int[] candidateCount, int[] firstCandidate,
                             boolean[] unresolvedPeer) {
    }

    private record PairDecision(int first, int second, ObservationState state) {
    }
}

package io.github.c1589190.bma3.app.channel.router;

import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPair;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPairObservations;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPairRelease;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPairWrite;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationUpdate;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceCandidateDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceCandidateRecord;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceOutboxEntry;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceOutboxPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.SuppressedFallbackWrite;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntent;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentWrite;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestPromotion;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservedAtCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservedAtObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteReason;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteUpdate;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.SourceBinding;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Narrow observation-ledger seam; production delegates to the SQLite channel store. */
public interface QqChannelObservationLedger {

    Optional<SourceBinding> sourceBinding(String rawSource);

    Optional<IngestIntent> findIngestIntent(String intentKey);

    Optional<IngestIntent> findIngestIntentByMessage(String rawSource, String channelMessageId);

    List<IngestIntent> pendingIngestIntents(String rawSource, int limit);

    IngestIntentPage pendingIngestIntents(long afterId, int limit);

    RouteObservationPage pendingRouteObservations(Set<RouteState> states, int limit,
                                                  String afterObservationId);

    IngestIntentWrite recordIngestIntent(IngestIntentDraft draft);

    IngestIntentWrite markIngestIntentBoardCommitted(String intentKey, long boardLocalSeq);

    IngestPromotion promoteIngestIntent(String intentKey, long boardLocalSeq);

    Optional<Observation> findObservation(String rawSource, String channelMessageId);

    ObservationPage observationsForThread(String threadId, Instant fromInclusive,
                                          Instant toExclusive, int limit);

    ObservationPage observationsForThread(String threadId, Instant fromInclusive,
                                          Instant toExclusive, ObservationCursor after, int limit);

    ObservedAtObservationPage observationsForThreadByObservedAt(
            String threadId, Instant fromInclusive, Instant toExclusive,
            ObservedAtCursor after, int limit);

    RouteUpdate decideRoute(String rawSource, String channelMessageId, RouteState target,
                            String itemId, RouteReason reason);

    RouteUpdate attachEvidenceItem(String rawSource, String channelMessageId, String itemId);

    Optional<CorrelationPair> findCorrelationPair(String rawSource, String channelMessageId);

    Optional<CorrelationPairObservations> correlationPairObservations(
            String rawSource, String channelMessageId);

    CorrelationPairWrite recordCorrelationPair(String firstRawSource, String firstMessageId,
                                               String secondRawSource, String secondMessageId,
                                               QqChannelObservationStore.CorrelationState state);

    CorrelationPairRelease releaseCorrelationPair(String rawSource, String channelMessageId);

    Observation recordCorrelation(CorrelationUpdate update);

    SuppressedFallbackWrite recordSuppressedFallbackCandidates(
            String sourceRawSource, String sourceChannelMessageId,
            List<EvidenceCandidateDraft> candidates);

    List<EvidenceCandidateRecord> evidenceCandidatesForSource(
            String rawSource, String channelMessageId);

    EvidenceOutboxPage pendingEvidenceOutbox(int limit, String afterObservationId);

    Optional<EvidenceOutboxEntry> evidenceOutboxEntry(String rawSource,
                                                       String channelMessageId);

    EvidenceOutboxEntry markEvidenceOutboxApplied(String rawSource,
                                                   String channelMessageId);

    EvidenceOutboxEntry markEvidenceOutboxTargetMissing(String rawSource,
                                                         String channelMessageId);

    static QqChannelObservationLedger from(QqChannelObservationStore store) {
        Objects.requireNonNull(store, "store");
        return new QqChannelObservationLedger() {
            @Override
            public Optional<SourceBinding> sourceBinding(String rawSource) {
                return store.sourceBinding(rawSource);
            }

            @Override
            public Optional<IngestIntent> findIngestIntent(String intentKey) {
                return store.findIngestIntent(intentKey);
            }

            @Override
            public Optional<IngestIntent> findIngestIntentByMessage(
                    String rawSource, String channelMessageId) {
                return store.findIngestIntentByMessage(rawSource, channelMessageId);
            }

            @Override
            public List<IngestIntent> pendingIngestIntents(String rawSource, int limit) {
                return store.pendingIngestIntents(rawSource, limit);
            }

            @Override
            public IngestIntentPage pendingIngestIntents(long afterId, int limit) {
                return store.pendingIngestIntents(afterId, limit);
            }

            @Override
            public RouteObservationPage pendingRouteObservations(
                    Set<RouteState> states, int limit, String afterObservationId) {
                return store.pendingRouteObservations(states, limit, afterObservationId);
            }

            @Override
            public IngestIntentWrite recordIngestIntent(IngestIntentDraft draft) {
                return store.recordIngestIntent(draft);
            }

            @Override
            public IngestIntentWrite markIngestIntentBoardCommitted(
                    String intentKey, long boardLocalSeq) {
                return store.markIngestIntentBoardCommitted(intentKey, boardLocalSeq);
            }

            @Override
            public IngestPromotion promoteIngestIntent(String intentKey, long boardLocalSeq) {
                return store.promoteIngestIntent(intentKey, boardLocalSeq);
            }

            @Override
            public Optional<Observation> findObservation(
                    String rawSource, String channelMessageId) {
                return store.findObservation(rawSource, channelMessageId);
            }

            @Override
            public ObservationPage observationsForThread(
                    String threadId, Instant fromInclusive, Instant toExclusive, int limit) {
                return store.observationsForThread(threadId, fromInclusive, toExclusive, limit);
            }

            @Override
            public ObservationPage observationsForThread(
                    String threadId, Instant fromInclusive, Instant toExclusive,
                    ObservationCursor after, int limit) {
                return store.observationsForThread(
                        threadId, fromInclusive, toExclusive, after, limit);
            }

            @Override
            public ObservedAtObservationPage observationsForThreadByObservedAt(
                    String threadId, Instant fromInclusive, Instant toExclusive,
                    ObservedAtCursor after, int limit) {
                return store.observationsForThreadByObservedAt(
                        threadId, fromInclusive, toExclusive, after, limit);
            }

            @Override
            public RouteUpdate decideRoute(String rawSource, String channelMessageId,
                                           RouteState target, String itemId,
                                           RouteReason reason) {
                return store.decideRoute(rawSource, channelMessageId, target, itemId, reason);
            }

            @Override
            public RouteUpdate attachEvidenceItem(
                    String rawSource, String channelMessageId, String itemId) {
                return store.attachEvidenceItem(rawSource, channelMessageId, itemId);
            }

            @Override
            public Optional<CorrelationPair> findCorrelationPair(
                    String rawSource, String channelMessageId) {
                return store.findCorrelationPair(rawSource, channelMessageId);
            }

            @Override
            public Optional<CorrelationPairObservations> correlationPairObservations(
                    String rawSource, String channelMessageId) {
                return store.correlationPairObservations(rawSource, channelMessageId);
            }

            @Override
            public CorrelationPairWrite recordCorrelationPair(
                    String firstRawSource, String firstMessageId,
                    String secondRawSource, String secondMessageId,
                    QqChannelObservationStore.CorrelationState state) {
                return store.recordCorrelationPair(
                        firstRawSource, firstMessageId, secondRawSource, secondMessageId, state);
            }

            @Override
            public CorrelationPairRelease releaseCorrelationPair(
                    String rawSource, String channelMessageId) {
                return store.releaseCorrelationPair(rawSource, channelMessageId);
            }

            @Override
            public Observation recordCorrelation(CorrelationUpdate update) {
                return store.recordCorrelation(update);
            }

            @Override
            public SuppressedFallbackWrite recordSuppressedFallbackCandidates(
                    String sourceRawSource, String sourceChannelMessageId,
                    List<EvidenceCandidateDraft> candidates) {
                return store.recordSuppressedFallbackCandidates(
                        sourceRawSource, sourceChannelMessageId, candidates);
            }

            @Override
            public List<EvidenceCandidateRecord> evidenceCandidatesForSource(
                    String rawSource, String channelMessageId) {
                return store.evidenceCandidatesForSource(rawSource, channelMessageId);
            }

            @Override
            public EvidenceOutboxPage pendingEvidenceOutbox(
                    int limit, String afterObservationId) {
                return store.pendingEvidenceOutbox(limit, afterObservationId);
            }

            @Override
            public Optional<EvidenceOutboxEntry> evidenceOutboxEntry(
                    String rawSource, String channelMessageId) {
                return store.evidenceOutboxEntry(rawSource, channelMessageId);
            }

            @Override
            public EvidenceOutboxEntry markEvidenceOutboxApplied(
                    String rawSource, String channelMessageId) {
                return store.markEvidenceOutboxApplied(rawSource, channelMessageId);
            }

            @Override
            public EvidenceOutboxEntry markEvidenceOutboxTargetMissing(
                    String rawSource, String channelMessageId) {
                return store.markEvidenceOutboxTargetMissing(rawSource, channelMessageId);
            }
        };
    }
}

package io.github.c1589190.bma3.app.channel.router;

import io.github.c1589190.bma3.app.channel.matching.ActiveChannelBinding;
import io.github.c1589190.bma3.app.channel.matching.ContentIntegrity;
import io.github.c1589190.bma3.app.channel.matching.CrossChannelMatcher;
import io.github.c1589190.bma3.app.channel.matching.IdentityMapping;
import io.github.c1589190.bma3.app.channel.matching.MatchInput;
import io.github.c1589190.bma3.app.channel.matching.MatchObservation;
import io.github.c1589190.bma3.app.channel.matching.MatchResult;
import io.github.c1589190.bma3.app.channel.matching.MatchingMode;
import io.github.c1589190.bma3.app.channel.matching.ObservationResult;
import io.github.c1589190.bma3.app.channel.matching.ObservationState;
import io.github.c1589190.bma3.app.channel.matching.TimeReliability;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ChannelAvailability;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPair;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationUpdate;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceCandidateDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceOutboxState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntent;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestPromotion;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservedAtCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservedAtObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteReason;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.SourceBinding;
import io.github.c1589190.bma3.app.channel.tools.ChannelMutationGate;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxEvidenceUpdate;
import io.github.c1589190.bma3.inbox.core.InboxItem;
import io.github.c1589190.bma3.inbox.core.ItemStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * App-side QQ dual-channel routing core.
 *
 * <p>The router is deliberately not wired into {@code Bma3App} by this class. It owns the
 * board-first protocol for a bound source and leaves every adapter/composition decision injectable:
 * priority is already evaluated, control snapshots come from {@link QqChannelControl}, the message
 * board from {@link QqChannelBoardPort}, channel facts from {@link QqChannelObservationLedger}, and
 * Inbox writes from {@link QqChannelInboxPort}.</p>
 *
 * <p>For a bound source the ordering is fixed: control snapshot -> body-free intent -> board row ->
 * board-commit marker -> observation promotion -> correlation/route -> Inbox receipt. For an
 * unbound OneBot source the legacy append/merge behavior is retained exactly, including the
 * board-duplicate early return. Source-level pause still writes the board fact but skips Inbox.</p>
 */
public final class QqChannelRouter {

    private static final int MAX_RECOVERY_PAGE = 500;
    private static final int MAX_WINDOW_OBSERVATIONS = 500;
    private static final int MAX_WINDOW_RAW_SCAN = 2_000;

    private final QqChannelBoardPort board;
    private final QqChannelObservationLedger ledger;
    private final QqChannelInboxPort inbox;
    private final QqChannelControl control;
    private final CrossChannelMatcher matcher;
    private final ChannelMutationGate mutationGate;
    private final Clock clock;
    private final Supplier<Duration> matchingToleranceSupplier;
    private final QqChannelPreviewFactory previewFactory;
    private final CrashHook crashHook;
    private final Function<String, String> threadLabelProvider;

    public QqChannelRouter(QqChannelBoardPort board,
                           QqChannelObservationLedger ledger,
                           QqChannelInboxPort inbox,
                           QqChannelControl control,
                           CrossChannelMatcher matcher,
                           ChannelMutationGate mutationGate,
                           Clock clock,
                           Duration matchingTolerance) {
        this(board, ledger, inbox, control, matcher, mutationGate, clock,
                fixedTolerance(matchingTolerance));
    }

    /**
     * Hot-reload-aware constructor. The supplier is read exactly once per new intent while the
     * shared gate is held, then frozen into the durable intent/observation snapshot. Recovery never
     * calls the supplier for an existing event.
     */
    public QqChannelRouter(QqChannelBoardPort board,
                           QqChannelObservationLedger ledger,
                           QqChannelInboxPort inbox,
                           QqChannelControl control,
                           CrossChannelMatcher matcher,
                           ChannelMutationGate mutationGate,
                           Clock clock,
                           Supplier<Duration> matchingToleranceSupplier) {
        this(board, ledger, inbox, control, matcher, mutationGate, clock,
                Objects.requireNonNull(matchingToleranceSupplier, "matchingToleranceSupplier"),
                QqChannelPreviewFactory.generic(), CrashHook.noop(), Function.identity());
    }

    /**
     * Production seam for both hot-reloaded tolerance and the logical-thread display label.
     * Existing intents retain their original tolerance after a configuration update.
     */
    public QqChannelRouter(QqChannelBoardPort board,
                           QqChannelObservationLedger ledger,
                           QqChannelInboxPort inbox,
                           QqChannelControl control,
                           CrossChannelMatcher matcher,
                           ChannelMutationGate mutationGate,
                           Clock clock,
                           Supplier<Duration> matchingToleranceSupplier,
                           Function<String, String> threadLabelProvider) {
        this(board, ledger, inbox, control, matcher, mutationGate, clock,
                Objects.requireNonNull(matchingToleranceSupplier, "matchingToleranceSupplier"),
                QqChannelPreviewFactory.generic(), CrashHook.noop(),
                Objects.requireNonNull(threadLabelProvider, "threadLabelProvider"));
    }

    /**
     * Production seam for the logical-thread display label. The provider is read only when a
     * canonical Inbox item body is created; recovery never substitutes a raw-source label.
     */
    public QqChannelRouter(QqChannelBoardPort board,
                           QqChannelObservationLedger ledger,
                           QqChannelInboxPort inbox,
                           QqChannelControl control,
                           CrossChannelMatcher matcher,
                           ChannelMutationGate mutationGate,
                           Clock clock,
                           Duration matchingTolerance,
                           Function<String, String> threadLabelProvider) {
        this(board, ledger, inbox, control, matcher, mutationGate, clock,
                fixedTolerance(matchingTolerance), QqChannelPreviewFactory.generic(),
                CrashHook.noop(),
                threadLabelProvider == null ? Function.identity() : threadLabelProvider);
    }

    QqChannelRouter(QqChannelBoardPort board,
                    QqChannelObservationLedger ledger,
                    QqChannelInboxPort inbox,
                    QqChannelControl control,
                    CrossChannelMatcher matcher,
                    ChannelMutationGate mutationGate,
                    Clock clock,
                    Duration matchingTolerance,
                    QqChannelPreviewFactory previewFactory,
                    CrashHook crashHook) {
        this(board, ledger, inbox, control, matcher, mutationGate, clock,
                fixedTolerance(matchingTolerance), previewFactory, crashHook,
                Function.identity());
    }

    QqChannelRouter(QqChannelBoardPort board,
                    QqChannelObservationLedger ledger,
                    QqChannelInboxPort inbox,
                    QqChannelControl control,
                    CrossChannelMatcher matcher,
                    ChannelMutationGate mutationGate,
                    Clock clock,
                    Supplier<Duration> matchingToleranceSupplier,
                    QqChannelPreviewFactory previewFactory,
                    CrashHook crashHook,
                    Function<String, String> threadLabelProvider) {
        this.board = Objects.requireNonNull(board, "board");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.control = Objects.requireNonNull(control, "control");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.mutationGate = Objects.requireNonNull(mutationGate, "mutationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.matchingToleranceSupplier = Objects.requireNonNull(
                matchingToleranceSupplier, "matchingToleranceSupplier");
        currentMatchingTolerance();
        this.previewFactory = Objects.requireNonNull(previewFactory, "previewFactory");
        this.crashHook = Objects.requireNonNull(crashHook, "crashHook");
        this.threadLabelProvider = Objects.requireNonNull(
                threadLabelProvider, "threadLabelProvider");
    }

    /** Route one message. The caller must have applied the D-027/D-028 priority gate. */
    public QqChannelRouteResult ingest(QqChannelRouteInput input) {
        Objects.requireNonNull(input, "input");
        if (input.dropped()) {
            return QqChannelRouteResult.dropped();
        }
        return mutationGate.underLock(() -> ingestLocked(input));
    }

    /**
     * Recover pending intents and route-pending observations for the <b>currently active</b>
     * source/thread topology before adapters accept new ingress.
     *
     * <p>Recovery enumerates the ledger globally, including intents/observations whose source is
     * currently unbound or whose binding epoch is no longer active. Each item is resumed from its
     * own persisted snapshot rather than from the current control plane.</p>
     *
     * <p>Must be called while no new ingress/control mutation is executing. The shared gate is
     * taken for each recovery unit, making repeated calls idempotent.</p>
     */
    public RecoveryReport recover() {
        int promoted = 0;
        int routed = 0;
        int skipped = 0;
        long intentCursor = 0L;
        while (true) {
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentPage page =
                    ledger.pendingIngestIntents(intentCursor, MAX_RECOVERY_PAGE);
            for (IngestIntent pending : page.items()) {
                RecoveryOutcome outcome = mutationGate.underLock(() -> recoverIntent(pending));
                if (outcome.promoted) {
                    promoted++;
                }
                if (outcome.routed) {
                    routed++;
                }
                if (outcome.skipped) {
                    skipped++;
                }
            }
            if (!page.hasMore()) {
                break;
            }
            if (page.nextAfterId() == intentCursor && page.items().isEmpty()) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.RECOVERY_FAILED,
                        "pending intent cursor 未推进");
            }
            intentCursor = page.nextAfterId();
        }

        String observationCursor = null;
        while (true) {
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteObservationPage page =
                    ledger.pendingRouteObservations(
                            Set.of(RouteState.PENDING_ROUTE, RouteState.DISPLAY_PENDING),
                            MAX_RECOVERY_PAGE, observationCursor);
            for (Observation observation : page.items()) {
                IngestIntent intent = ledger.findIngestIntentByMessage(
                                observation.rawSource(), observation.channelMessageId())
                        .orElse(null);
                if (intent == null) {
                    skipped++;
                    continue;
                }
                QqChannelRouteResult result = mutationGate.underLock(
                        () -> routeObservation(intent, observation, inputFromObservation(intent,
                                observation), observation.routeState() == RouteState.PENDING_ROUTE));
                if (result.disposition() == QqChannelRouteResult.Disposition.BOUND_DISPLAY
                        || result.disposition()
                        == QqChannelRouteResult.Disposition.BOUND_EVIDENCE) {
                    routed++;
                }
            }
            if (!page.hasMore()) {
                break;
            }
            observationCursor = page.nextObservationId().orElseThrow(() ->
                    new QqChannelRouterException(
                            QqChannelRouterException.Code.RECOVERY_FAILED,
                            "pending route observation cursor 缺失"));
        }

        String evidenceCursor = null;
        while (true) {
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceOutboxPage page =
                    ledger.pendingEvidenceOutbox(MAX_RECOVERY_PAGE, evidenceCursor);
            for (var entry : page.items()) {
                mutationGate.underLock(() -> {
                    appendEvidence(entry.targetItemId(), entry.observation(),
                            entry.observation().correlationState());
                    return null;
                });
                routed++;
            }
            if (!page.hasMore()) {
                break;
            }
            evidenceCursor = page.nextObservationId().orElseThrow(() ->
                    new QqChannelRouterException(
                            QqChannelRouterException.Code.RECOVERY_FAILED,
                            "pending evidence outbox cursor 缺失"));
        }
        return new RecoveryReport(promoted, routed, skipped);
    }

    public record RecoveryReport(int promoted, int routed, int skipped) {
    }

    private QqChannelRouteResult ingestLocked(QqChannelRouteInput input) {
        Channel channel = channelOf(input.source());
        Optional<Message> existing = board.findInbound(input.source(), input.messageId());
        Optional<IngestIntent> existingIntent = ledger.findIngestIntentByMessage(
                input.source().format(), input.messageId());
        if (existingIntent.isPresent()) {
            return continueIntent(existingIntent.orElseThrow(), existing, input);
        }

        String rawRequestKey = requestKey(input.source().format(), input.messageId());
        if (inbox.isRawSuppressed(rawRequestKey, input.source().format(),
                channel.wireName())) {
            // This event was deliberately paused while unbound. Even if the source is now bound,
            // it must remain a raw, non-waking board fact. The tombstone is checked before a bound
            // intent so a raw receipt can never conflict with a canonical append key.
            if (existing.isEmpty()) {
                board.append(input.boardMessage());
            }
            return new QqChannelRouteResult(QqChannelRouteResult.Disposition.UNBOUND_RAW,
                    "raw", "raw_suppressed_gate_off", input.source().format(),
                    channel.wireName(), "", "");
        }

        QqChannelControl.SourceState state = control.sourceState(
                input.source().format(), input.senderId(), channel);
        if (state.binding().isEmpty()) {
            return legacyRaw(input, state.sourceGateEnabled(), existing);
        }
        QqChannelControl.ControlSnapshot binding = state.binding().orElseThrow();
        if (existing.isPresent()
                && existing.orElseThrow().localSeq() < binding.startBoardSeq()) {
            return legacyRaw(input, state.sourceGateEnabled(), existing);
        }
        if (existing.isPresent()) {
            throw new QqChannelRouterException(
                    QqChannelRouterException.Code.ORPHAN_BOUND_BOARD_ROW,
                    "post-binding board row 没有 ingest intent");
        }

        IngestIntentDraft intent = intentDraft(input, binding);
        ledger.recordIngestIntent(intent);
        crashHook.at(CrashPoint.AFTER_INTENT);
        AppendResult appended = board.append(input.boardMessage());
        crashHook.at(CrashPoint.AFTER_BOARD);
        ledger.markIngestIntentBoardCommitted(intent.intentKey(),
                appended.message().localSeq());
        crashHook.at(CrashPoint.AFTER_BOARD_COMMITTED);
        IngestPromotion promotion = ledger.promoteIngestIntent(intent.intentKey(),
                appended.message().localSeq());
        crashHook.at(CrashPoint.AFTER_PROMOTION);
        return routeObservation(promotion.intent(), promotion.observation(), input, true);
    }

    private QqChannelRouteResult continueIntent(IngestIntent intent, Optional<Message> existing,
                                                 QqChannelRouteInput input) {
        long boardLocalSeq;
        if (existing.isPresent()) {
            Message boardFact = existing.orElseThrow();
            if (!sameReplayFact(intent, boardFact.inbound(), input)) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.INVALID_INPUT,
                        "replay 的 board fact 与已持久化 fact 不一致");
            }
            boardLocalSeq = boardFact.localSeq();
        } else {
            InboundMessage incoming = input.boardMessage();
            if (!intent.senderId().equals(incoming.senderId())
                    || !intent.direction().name().equals(incoming.direction().name())
                    || (intent.timeReliability() == io.github.c1589190.bma3.app.channel.store
                            .QqChannelObservationStore.TimeReliability.RELIABLE
                        && (input.timeReliability() != intent.timeReliability()
                            || !intent.messageAt().equals(incoming.at())))) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.INVALID_INPUT,
                        "replay 的发送者、方向或可靠时间与已持久化 intent 不一致");
            }
            // A platform timestamp may be absent: on replay the adapter's fallback receive time
            // changes. Freeze the first accepted timestamp rather than creating a board row that
            // disagrees with the already durable intent.
            InboundMessage frozen = new InboundMessage(incoming.source(), incoming.messageId(),
                    intent.messageAt(), incoming.segments(), incoming.direction(),
                    incoming.senderId(), incoming.senderName(), incoming.senderCard(),
                    incoming.senderRole());
            AppendResult appended = board.append(frozen);
            crashHook.at(CrashPoint.AFTER_BOARD);
            boardLocalSeq = appended.message().localSeq();
        }
        Observation observation = promoteOrFind(intent, boardLocalSeq);
        crashHook.at(CrashPoint.AFTER_PROMOTION);
        return routeObservation(intent, observation, input, true);
    }

    private static boolean sameReplayFact(IngestIntent intent, InboundMessage stored,
                                          QqChannelRouteInput input) {
        InboundMessage incoming = input.boardMessage();
        if (!stored.source().equals(incoming.source())
                || !stored.messageId().equals(incoming.messageId())
                || stored.direction() != incoming.direction()
                || !stored.senderId().equals(incoming.senderId())
                || !stored.segments().equals(incoming.segments())) {
            return false;
        }
        // Names/cards/roles are presentation metadata and may legitimately change between
        // deliveries. The first board row remains authoritative. A fallback timestamp can also
        // change on replay; reliable platform timestamps, however, must remain identical.
        if (intent.timeReliability() == io.github.c1589190.bma3.app.channel.store
                .QqChannelObservationStore.TimeReliability.RELIABLE) {
            return input.timeReliability() == intent.timeReliability()
                    && stored.at().equals(incoming.at());
        }
        return true;
    }

    private QqChannelRouteResult legacyRaw(QqChannelRouteInput input, boolean sourceGateEnabled,
                                           Optional<Message> existing) {
        Channel channel = channelOf(input.source());
        if (!sourceGateEnabled) {
            if (input.rawProjectionMode() == QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE) {
                // Persist the "this raw event was deliberately suppressed" decision before the
                // board fact. A later gate re-enable then still sees the durable receipt and cannot
                // back-inject this paused message.
                inbox.suppressRawOnce(rawRequestKey(input),
                        input.source().format(), channel.wireName());
                crashHook.at(CrashPoint.AFTER_RAW_SUPPRESSION);
            }
            if (existing.isEmpty()) {
                board.append(input.boardMessage());
            }
            return new QqChannelRouteResult(QqChannelRouteResult.Disposition.BOUND_GATE_OFF,
                    "raw", "source_gate_off", "", "", "", "");
        }
        if (input.rawProjectionMode() == QqChannelRouteInput.RawProjectionMode
                .LEGACY_APPEND_OR_MERGE) {
            if (existing.isPresent()) {
                return new QqChannelRouteResult(
                        QqChannelRouteResult.Disposition.DUPLICATE, "raw", "board_duplicate",
                        "", "", "", "");
            }
            AppendResult appended = board.append(input.boardMessage());
            if (!appended.created()) {
                return new QqChannelRouteResult(
                        QqChannelRouteResult.Disposition.DUPLICATE, "raw", "board_duplicate",
                        "", "", "", "");
            }
            InboxEntry preview = withLocalSeq(input.inboxEntry(),
                    appended.message().localSeq(), channel);
            InboxItem item = inbox.appendOrMerge(AppendItem.of(
                    rawSourceView(input), preview, input.priority(), input.observedAt()));
            return new QqChannelRouteResult(QqChannelRouteResult.Disposition.UNBOUND_RAW,
                    "raw", "legacy_append_or_merge", input.source().format(),
                    channel.wireName(), item == null ? "" : item.id(), "");
        }

        Message rawFact = existing.orElseGet(() -> board.append(input.boardMessage()).message());
        String rawKey = requestKey(input.source().format(), input.messageId());
        InboxEntry preview = withLocalSeq(input.inboxEntry(),
                rawFact.localSeq(), channel);
        AppendItem item = AppendItem.projected(rawSourceView(input), preview,
                input.priority(), input.observedAt(), channel.wireName(),
                summary(input, null, "UNBOUND_RAW"));
        InboxItem projected = inbox.appendProjectedOnce(rawKey, item);
        return new QqChannelRouteResult(QqChannelRouteResult.Disposition.UNBOUND_RAW,
                "raw", "raw_receipt_once", input.source().format(), channel.wireName(),
                projected == null ? "" : projected.id(), "");
    }

    private IngestIntentDraft intentDraft(QqChannelRouteInput input,
                                          QqChannelControl.ControlSnapshot binding) {
        boolean gateEnabled = binding.gateEnabled();
        return new IngestIntentDraft(
                intentKey(input.source().format(), input.messageId()),
                binding.threadId(), binding.channel(), binding.accountId(),
                input.source().format(), input.messageId(), input.senderId(),
                binding.canonicalSenderId().isBlank()
                        ? Optional.empty() : Optional.of(binding.canonicalSenderId()),
                binding.identityStatus(), storeDirection(input.direction()), input.messageAt(),
                input.observedAt(), input.priority(), input.timeReliability(),
                input.contentIntegrity(), input.structuredMentionsTrusted(),
                binding.bindingGeneration(), gateEnabled,
                binding.bindingEpochId(),
                gateEnabled ? RouteReason.PENDING : RouteReason.INJECTION_GATE_OFF,
                binding.preferredChannel(), binding.preferredChannelAvailability(),
                binding.selectedChannel(), binding.matchingMode(),
                Math.toIntExact(currentMatchingTolerance().toSeconds()),
                binding.identityRevision(),
                gateEnabled ? RouteState.PENDING_ROUTE : RouteState.GATE_OFF, null);
    }

    private Observation promoteOrFind(IngestIntent intent, long boardLocalSeq) {
        IngestIntent current = ledger.findIngestIntent(intent.intentKey()).orElse(intent);
        return switch (current.state()) {
            case RECORDED -> {
                ledger.markIngestIntentBoardCommitted(current.intentKey(), boardLocalSeq);
                yield ledger.promoteIngestIntent(current.intentKey(), boardLocalSeq).observation();
            }
            case BOARD_COMMITTED -> ledger.promoteIngestIntent(
                    current.intentKey(), boardLocalSeq).observation();
            case OBSERVATION_COMMITTED -> ledger.findObservation(
                            current.rawSource(), current.channelMessageId())
                    .orElseThrow(() -> new QqChannelRouterException(
                            QqChannelRouterException.Code.BINDING_LEDGER_MISMATCH,
                            "intent 已完成但 observation 缺失"));
        };
    }

    private QqChannelRouteResult routeObservation(IngestIntent intent, Observation observation,
                                                   QqChannelRouteInput input,
                                                   boolean allowCorrelation) {
        if (observation.routeState() == RouteState.GATE_OFF) {
            return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_GATE_OFF,
                    "gate_off", "INJECTION_GATE_OFF", "", "");
        }
        if (observation.routeState() == RouteState.DISPLAY_LINKED) {
            // The body is already durable. Repair a peer evidence target that was not persisted
            // before a crash, but never create another item or another wake.
            if (observation.itemId().isPresent()) {
                observationPeer(intent, observation).ifPresent(peer ->
                        ensurePeerEvidenceTarget(peer, observation.itemId().orElseThrow()));
            }
            return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_DISPLAY,
                    "canonical", "already_linked", canonicalSource(intent.threadId()),
                    observation.itemId().orElse(""));
        }

        if (observation.routeState() == RouteState.EVIDENCE_ONLY) {
            // Target inference is forbidden. A deferred/channel-only observation with no target
            // must never attach itself to whichever item happens to be open now. Only an explicit
            // target persisted with the observation is recoverable.
            if (observation.itemId().isPresent()) {
                String target = observation.itemId().orElseThrow();
                appendEvidence(target, observation, observation.correlationState());
                return result(intent, observation,
                        QqChannelRouteResult.Disposition.BOUND_EVIDENCE, "evidence",
                        "evidence_outbox_replay", canonicalSource(intent.threadId()), target);
            }
            return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_EVIDENCE,
                    "evidence", observation.routeReason().name(), "", "");
        }

        if (observation.routeState() == RouteState.DISPLAY_PENDING) {
            String itemId = observation.itemId().orElseThrow();
            if (inbox.item(itemId).isPresent()) {
                Optional<Observation> peer = observationPeer(intent, observation);
                peer.ifPresent(value -> ensurePeerEvidenceTarget(value, itemId));
                ledger.decideRoute(observation.rawSource(), observation.channelMessageId(),
                        RouteState.DISPLAY_LINKED, null, observation.routeReason());
                crashHook.at(CrashPoint.AFTER_ROUTE_LINK);
                peer.ifPresent(value -> appendEvidence(
                        itemId, value, observation.correlationState()));
                return result(intent, observation,
                        QqChannelRouteResult.Disposition.BOUND_DISPLAY, "canonical",
                        "display_recovered", canonicalSource(intent.threadId()), itemId);
            }
            // Body was not written; fall through and replay the same durable request key.
        }

        CorrelationOutcome correlation;
        if (allowCorrelation) {
            correlation = applyCorrelation(intent, observation);
        } else {
            correlation = persistedCorrelation(observation);
            if (correlation.peer().isEmpty()
                    && observation.routeState() == RouteState.PENDING_ROUTE) {
                // No durable pair from the interrupted first attempt: PENDING_ROUTE is the one
                // state where recomputing from persisted facts is safe and necessary.
                correlation = applyCorrelation(intent, observation);
            }
        }
        crashHook.at(CrashPoint.AFTER_CORRELATION);
        if (correlation.truncated()) {
            return evidenceOnly(intent, observation, null, CorrelationState.UNVERIFIED,
                    RouteReason.NON_PREFERRED_EVIDENCE, "window_overflow_fail_closed");
        }
        Observation peer = correlation.peer().orElse(null);
        Optional<InboxItem> active = inbox.activeForSource(canonicalSource(intent.threadId()));

        Optional<String> peerItem = peer != null
                && (peer.routeState() == RouteState.DISPLAY_LINKED
                || peer.routeState() == RouteState.DISPLAY_PENDING)
                ? peer.itemId() : Optional.empty();
        if (peerItem.isPresent()) {
            return evidenceOnly(intent, observation, peerItem.orElseThrow(), correlation.state(),
                    RouteReason.LATE_EVIDENCE_FROZEN_ITEM, "peer_display_item");
        }

        if (observation.routeState() == RouteState.DISPLAY_PENDING) {
            return createOrMergeBody(intent, observation, input, correlation, active);
        }

        if (active.isPresent()) {
            InboxItem item = active.orElseThrow();
            if (item.displayChannel().equals(observation.channel().wireName())) {
                return createOrMergeBody(intent, observation, input, correlation, active);
            }
            return evidenceOnly(intent, observation, item.id(), correlation.state(),
                    RouteReason.NON_PREFERRED_EVIDENCE, "non_display_open_item");
        }

        Channel selected = intent.selectedChannel().orElse(null);
        if (selected == null || selected != observation.channel()) {
            return evidenceOnly(intent, observation, null, correlation.state(),
                    RouteReason.NON_PREFERRED_EVIDENCE, "preferred_not_observed");
        }

        if (isFallback(intent)) {
            SuppressionScan scan = suppressionCandidates(intent, observation);
            List<FallbackSuppression.Candidate> candidates = scan.candidates();
            if (candidates.isEmpty() && scan.truncated()) {
                recordUnverified(observation);
                ledger.decideRoute(observation.rawSource(), observation.channelMessageId(),
                        RouteState.EVIDENCE_ONLY, null, RouteReason.NON_PREFERRED_EVIDENCE);
                return result(intent, observation,
                        QqChannelRouteResult.Disposition.BOUND_EVIDENCE, "evidence",
                        "suppression_window_overflow_fail_closed", "", "");
            }
            if (!candidates.isEmpty()) {
                List<EvidenceCandidateDraft> drafts = candidates.stream()
                        .map(candidate -> new EvidenceCandidateDraft(
                                candidate.observation().rawSource(),
                                candidate.observation().channelMessageId(),
                                candidate.itemId()))
                        .toList();
                String uniqueItem = candidates.stream()
                        .map(FallbackSuppression.Candidate::itemId)
                        .filter(item -> !item.isBlank())
                        .distinct()
                        .count() == 1
                        ? candidates.getFirst().itemId() : "";
                if (!uniqueItem.isBlank()) {
                    // Persist the target/outbox before the candidate/Inbox side effect. A crash
                    // then leaves either a recoverable outbox target or a fully recorded
                    // suppression edge, never an in-memory-only decision.
                    ledger.attachEvidenceItem(observation.rawSource(),
                            observation.channelMessageId(), uniqueItem);
                }
                ledger.recordSuppressedFallbackCandidates(
                        observation.rawSource(), observation.channelMessageId(), drafts);
                if (!uniqueItem.isBlank()) {
                    crashHook.at(CrashPoint.AFTER_EVIDENCE_ROUTE);
                    appendEvidence(uniqueItem, observation, CorrelationState.UNVERIFIED);
                }
                return result(intent, observation,
                        QqChannelRouteResult.Disposition.BOUND_EVIDENCE, "evidence",
                        RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED.name(),
                        uniqueItem.isBlank() ? "" : canonicalSource(intent.threadId()),
                        uniqueItem);
            }
        }
        return createOrMergeBody(intent, observation, input, correlation, active);
    }

    private QqChannelRouteResult createOrMergeBody(IngestIntent intent, Observation observation,
                                                    QqChannelRouteInput input,
                                                    CorrelationOutcome correlation,
                                                    Optional<InboxItem> active) {
        Channel channel = observation.channel();
        InboxEntry entry = entryFor(input, observation, channel);
        String canonical = canonicalSource(intent.threadId());
        Map<String, Object> summary = summary(input, observation, correlation.state().name());
        String threadLabel = threadLabelProvider.apply(intent.threadId());
        if (threadLabel == null || threadLabel.isBlank()) {
            threadLabel = intent.threadId();
        }
        AppendItem request = AppendItem.projected(
                SourceView.of(canonical, threadLabel), entry, observation.priority(),
                clock.instant(), channel.wireName(), summary);
        InboxItem item = inbox.appendProjectedOnce(intent.requestKey(), request);
        crashHook.at(CrashPoint.AFTER_INBOX_BODY);
        if (item == null) {
            return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_EVIDENCE,
                    "canonical", "inbox_receipt_item_cleaned", canonical, "");
        }
        RouteReason reason = isFallback(intent)
                ? RouteReason.FALLBACK_DISPLAY : RouteReason.PREFERRED_DISPLAY;
        ledger.decideRoute(observation.rawSource(), observation.channelMessageId(),
                RouteState.DISPLAY_PENDING, item.id(), reason);
        crashHook.at(CrashPoint.AFTER_DISPLAY_PENDING);
        if (correlation.peer().isPresent()) {
            ensurePeerEvidenceTarget(correlation.peer().orElseThrow(), item.id());
            crashHook.at(CrashPoint.AFTER_PEER_EVIDENCE_TARGET);
        }
        crashHook.at(CrashPoint.AFTER_ROUTE_LINK);
        ledger.decideRoute(observation.rawSource(), observation.channelMessageId(),
                RouteState.DISPLAY_LINKED, null, reason);
        crashHook.at(CrashPoint.AFTER_DISPLAY_LINKED);
        if (correlation.peer().isPresent()) {
            appendEvidence(item.id(), correlation.peer().orElseThrow(), correlation.state());
        }
        return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_DISPLAY,
                "canonical", reason.name(), canonical, item.id());
    }

    private Optional<Observation> observationPeer(IngestIntent intent, Observation observation) {
        return ledger.correlationPairObservations(
                        observation.rawSource(), observation.channelMessageId())
                .map(pair -> pair.first().observationId().equals(observation.observationId())
                        ? pair.second() : pair.first());
    }

    private CorrelationOutcome persistedCorrelation(Observation observation) {
        return ledger.correlationPairObservations(
                        observation.rawSource(), observation.channelMessageId())
                .map(pair -> new CorrelationOutcome(
                        pair.pair().state(),
                        Optional.of(pair.first().observationId()
                                .equals(observation.observationId())
                                ? pair.second() : pair.first()), false))
                .orElseGet(CorrelationOutcome::none);
    }

    private void ensurePeerEvidenceTarget(Observation peer, String itemId) {
        if (peer.itemId().filter(itemId::equals).isPresent()) {
            return;
        }
        if (peer.routeState() == RouteState.EVIDENCE_ONLY) {
            if (peer.itemId().isEmpty()) {
                ledger.attachEvidenceItem(peer.rawSource(), peer.channelMessageId(), itemId);
            } else if (!peer.itemId().orElseThrow().equals(itemId)) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.CORRELATION_APPLY_FAILED,
                        "peer evidence target 与 display item 冲突");
            }
        } else if (peer.routeState() == RouteState.PENDING_ROUTE) {
            ledger.decideRoute(peer.rawSource(), peer.channelMessageId(),
                    RouteState.EVIDENCE_ONLY, itemId, RouteReason.NON_PREFERRED_EVIDENCE);
        } else if (peer.routeState() == RouteState.DISPLAY_PENDING
                || peer.routeState() == RouteState.DISPLAY_LINKED) {
            if (!peer.itemId().filter(itemId::equals).isPresent()) {
                throw new QqChannelRouterException(
                        QqChannelRouterException.Code.CORRELATION_APPLY_FAILED,
                        "paired display observation 已属于其他 item");
            }
        }
    }

    private QqChannelRouteResult evidenceOnly(IngestIntent intent, Observation observation,
                                              String itemId, CorrelationState state,
                                              RouteReason reason, String routeReason) {
        if (observation.routeState() == RouteState.PENDING_ROUTE) {
            ledger.decideRoute(observation.rawSource(), observation.channelMessageId(),
                    RouteState.EVIDENCE_ONLY, itemId, reason);
        } else if (observation.routeState() == RouteState.EVIDENCE_ONLY
                && observation.itemId().isEmpty() && itemId != null && !itemId.isBlank()) {
            ledger.attachEvidenceItem(observation.rawSource(),
                    observation.channelMessageId(), itemId);
        }
        if (itemId != null && !itemId.isBlank()) {
            crashHook.at(CrashPoint.AFTER_EVIDENCE_ROUTE);
            appendEvidence(itemId, observation, state);
        }
        return result(intent, observation, QqChannelRouteResult.Disposition.BOUND_EVIDENCE,
                "evidence", routeReason, canonicalSource(intent.threadId()),
                itemId == null ? "" : itemId);
    }

    private void appendEvidence(String itemId, Observation observation, CorrelationState state) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        var outbox = ledger.evidenceOutboxEntry(
                observation.rawSource(), observation.channelMessageId());
        if (outbox.isPresent() && outbox.orElseThrow().state() != EvidenceOutboxState.PENDING) {
            return;
        }
        if (inbox.item(itemId).isEmpty()) {
            if (outbox.isPresent()) {
                ledger.markEvidenceOutboxTargetMissing(
                        observation.rawSource(), observation.channelMessageId());
            }
            return;
        }
        // One observation contributes at most one evidence row/count to one item. Correlation can
        // later be queried from the channel ledger; the Inbox summary must not accumulate the same
        // observation again when its state is re-evaluated.
        String key = "obs:" + observation.observationId();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("observationId", observation.observationId());
        evidence.put("rawSource", observation.rawSource());
        evidence.put("messageId", observation.channelMessageId());
        evidence.put("state", state.name());
        Map<String, Object> patch = Map.of("lastCorrelationState", state.name());
        try {
            if (state == CorrelationState.MATCHED) {
                inbox.appendEvidenceOnly(InboxEvidenceUpdate.matchedKeyed(
                        itemId, "", key, observation.channel().wireName(), evidence, patch,
                        observation.priority()));
            } else {
                inbox.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(
                        itemId, key, observation.channel().wireName(), evidence));
            }
            if (outbox.isPresent()) {
                ledger.markEvidenceOutboxApplied(
                        observation.rawSource(), observation.channelMessageId());
            }
            crashHook.at(CrashPoint.AFTER_EVIDENCE);
        } catch (IllegalArgumentException failure) {
            // Retention may remove the target between the existence check and the write. Only that
            // exact race is ignorable; any other argument failure while the item still exists is a
            // real evidence write failure and must reach the gateway/router.
            if (inbox.item(itemId).isPresent()) {
                throw failure;
            }
            if (outbox.isPresent()) {
                ledger.markEvidenceOutboxTargetMissing(
                        observation.rawSource(), observation.channelMessageId());
            }
        }
    }

    private CorrelationOutcome applyCorrelation(IngestIntent intent, Observation current) {
        Optional<CorrelationPair> existingPair = ledger.findCorrelationPair(
                current.rawSource(), current.channelMessageId());
        if (intent.matchingMode() == io.github.c1589190.bma3.app.channel.store
                .QqChannelObservationStore.MatchingMode.OFF) {
            if (existingPair.isEmpty()) {
                recordUnverified(current);
                return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), false);
            }
            return new CorrelationOutcome(existingPair.orElseThrow().state(), Optional.empty(), false);
        }
        if (current.senderId().isBlank()) {
            releaseIfPresent(current, existingPair);
            recordUnverified(current);
            return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), false);
        }

        Duration tolerance = Duration.ofSeconds(intent.strictTimeToleranceSeconds());
        WindowScan window = window(intent, current, tolerance);
        if (window.truncated()) {
            releaseIfPresent(current, existingPair);
            recordUnverified(current);
            return CorrelationOutcome.overflow();
        }
        MatchResult matchResult;
        try {
            matchResult = matcher.recompute(matchInput(intent, current, window.items(), tolerance));
        } catch (IllegalArgumentException failure) {
            releaseIfPresent(current, existingPair);
            recordUnverified(current);
            return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), false);
        }
        ObservationResult currentResult = matchResult.observations().stream()
                .filter(result -> result.observation().observationId()
                        .equals(current.observationId()))
                .findFirst().orElse(null);
        if (currentResult == null) {
            releaseIfPresent(current, existingPair);
            recordUnverified(current);
            return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), false);
        }
        CorrelationState state = mapState(currentResult.state());
        Optional<Observation> peer = currentResult.peer()
                .flatMap(ref -> ledger.findObservation(ref.rawSource(), ref.channelMessageId()))
                .filter(observation -> !observation.observationId()
                        .equals(current.observationId()));
        if (peer.isEmpty() || !correlationNeedsPeer(state)) {
            releaseIfPresent(current, existingPair);
            if (state == CorrelationState.CHANNEL_ONLY) {
                ledger.recordCorrelation(CorrelationUpdate.channelOnly(
                        current.rawSource(), current.channelMessageId()));
            } else {
                recordUnverified(current);
            }
            return new CorrelationOutcome(state, Optional.empty(), false);
        }

        Observation peerObservation = peer.orElseThrow();
        optionalReleaseDifferentPair(current, existingPair, peerObservation.observationId());
        releasePeerPairIfNotCurrent(peerObservation, current);
        ledger.recordCorrelationPair(current.rawSource(), current.channelMessageId(),
                peerObservation.rawSource(), peerObservation.channelMessageId(), state);
        return new CorrelationOutcome(state, Optional.of(peerObservation), false);
    }

    private WindowScan window(IngestIntent intent, Observation current,
                              Duration tolerance) {
        Instant from = current.messageAt().minus(tolerance);
        Instant to = current.messageAt().plus(tolerance).plusMillis(1);
        List<Observation> matchingEpoch = new ArrayList<>();
        ObservationCursor cursor = null;
        int rawScanned = 0;
        while (true) {
            ObservationPage page = ledger.observationsForThread(
                    intent.threadId(), from, to, cursor, MAX_RECOVERY_PAGE);
            for (Observation observation : page.items()) {
                rawScanned++;
                if (observation.bindingEpochId().equals(intent.bindingEpochId())) {
                    if (matchingEpoch.size() >= MAX_WINDOW_OBSERVATIONS) {
                        return new WindowScan(matchingEpoch, true);
                    }
                    matchingEpoch.add(observation);
                }
            }
            if (!page.hasMore()) {
                return new WindowScan(matchingEpoch, false);
            }
            if (rawScanned >= MAX_WINDOW_RAW_SCAN) {
                return new WindowScan(matchingEpoch, true);
            }
            cursor = page.nextCursor().orElseThrow(() ->
                    new QqChannelRouterException(
                            QqChannelRouterException.Code.RECOVERY_FAILED,
                            "matching window cursor 缺失"));
        }
    }

    private WindowScan observedWindow(IngestIntent intent, Observation current,
                                      Duration tolerance) {
        Instant from = current.observedAt().minus(tolerance);
        Instant to = current.observedAt().plus(tolerance).plusMillis(1);
        List<Observation> matchingEpoch = new ArrayList<>();
        ObservedAtCursor cursor = null;
        int rawScanned = 0;
        while (true) {
            ObservedAtObservationPage page = ledger.observationsForThreadByObservedAt(
                    intent.threadId(), from, to, cursor, MAX_RECOVERY_PAGE);
            for (Observation observation : page.items()) {
                rawScanned++;
                if (observation.bindingEpochId().equals(intent.bindingEpochId())) {
                    if (matchingEpoch.size() >= MAX_WINDOW_OBSERVATIONS) {
                        return new WindowScan(matchingEpoch, true);
                    }
                    matchingEpoch.add(observation);
                }
            }
            if (!page.hasMore()) {
                return new WindowScan(matchingEpoch, false);
            }
            if (rawScanned >= MAX_WINDOW_RAW_SCAN) {
                return new WindowScan(matchingEpoch, true);
            }
            cursor = page.nextCursor().orElseThrow(() ->
                    new QqChannelRouterException(
                            QqChannelRouterException.Code.RECOVERY_FAILED,
                            "observed-at window cursor 缺失"));
        }
    }

    private MatchInput matchInput(IngestIntent intent, Observation current,
                                  List<Observation> observations, Duration tolerance) {
        List<MatchObservation> matchObservations = new ArrayList<>();
        Map<io.github.c1589190.bma3.app.channel.matching.Channel, ActiveChannelBinding> bindings =
                new LinkedHashMap<>();
        List<IdentityMapping> identities = new ArrayList<>();
        for (Observation observation : observations) {
            if (observation.senderId().isBlank()) {
                continue;
            }
            Optional<Message> fact = board.findInbound(
                    SourceAddress.parse(observation.rawSource()), observation.channelMessageId());
            if (fact.isEmpty()) {
                continue;
            }
            io.github.c1589190.bma3.app.channel.matching.Channel matchChannel =
                    matcherChannel(observation.channel());
            bindings.putIfAbsent(matchChannel, new ActiveChannelBinding(
                    intent.threadId(), matchChannel, observation.accountId(),
                    observation.rawSource()));
            matchObservations.add(new MatchObservation(
                    observation.observationId(), intent.threadId(), matchChannel,
                    observation.accountId(), observation.rawSource(),
                    observation.channelMessageId(), observation.senderId(),
                    io.github.c1589190.bma3.core.message.Direction.valueOf(
                            observation.direction().name()),
                    observation.messageAt(), mapIntegrity(observation.contentIntegrity()),
                    mapTime(observation.timeReliability()),
                    observation.structuredMentionsTrusted(),
                    fact.orElseThrow().inbound().segments()));
            observation.canonicalSenderId().filter(value -> !value.isBlank())
                    .ifPresent(canonical -> identities.add(new IdentityMapping(
                            intent.threadId(), matchChannel, observation.senderId(), canonical,
                            IdentityMapping.ScopeType.SOURCE, observation.rawSource())));
        }
        if (matchObservations.stream().noneMatch(observation -> observation.observationId()
                .equals(current.observationId()))) {
            throw new IllegalArgumentException("current observation cannot enter matcher");
        }
        MatchingMode mode = switch (intent.matchingMode()) {
            case STRICT -> MatchingMode.STRICT;
            case LOOSE -> MatchingMode.LOOSE;
            case OFF -> MatchingMode.OFF;
        };
        return new MatchInput(intent.threadId(), mode, tolerance,
                List.copyOf(bindings.values()), identities, matchObservations);
    }

    private SuppressionScan suppressionCandidates(IngestIntent intent,
                                                    Observation current) {
        Duration tolerance = Duration.ofSeconds(intent.strictTimeToleranceSeconds());
        WindowScan byMessageAt = window(intent, current, tolerance);
        WindowScan byObservedAt = observedWindow(intent, current, tolerance);
        if (byMessageAt.truncated() || byObservedAt.truncated()) {
            return new SuppressionScan(List.of(), true);
        }
        Map<String, Observation> union = new LinkedHashMap<>();
        for (Observation observation : byMessageAt.items()) {
            union.putIfAbsent(observation.observationId(), observation);
        }
        for (Observation observation : byObservedAt.items()) {
            union.putIfAbsent(observation.observationId(), observation);
        }
        return new SuppressionScan(new FallbackSuppression().findCandidates(
                current, List.copyOf(union.values()), tolerance,
                observation -> board.findInbound(
                        SourceAddress.parse(observation.rawSource()),
                        observation.channelMessageId())), false);
    }

    private void releasePeerPairIfNotCurrent(Observation peer, Observation current) {
        Optional<CorrelationPair> peerPair = ledger.findCorrelationPair(
                peer.rawSource(), peer.channelMessageId());
        if (peerPair.isEmpty()) {
            return;
        }
        CorrelationPair pair = peerPair.orElseThrow();
        boolean samePair = pair.observationAId().equals(current.observationId())
                || pair.observationBId().equals(current.observationId());
        if (!samePair) {
            ledger.releaseCorrelationPair(peer.rawSource(), peer.channelMessageId());
        }
    }

    private void recordUnverified(Observation observation) {
        ledger.recordCorrelation(CorrelationUpdate.unverified(
                observation.rawSource(), observation.channelMessageId()));
    }

    private void releaseIfPresent(Observation current, Optional<CorrelationPair> pair) {
        if (pair.isPresent()) {
            ledger.releaseCorrelationPair(current.rawSource(), current.channelMessageId());
        }
    }

    private void optionalReleaseDifferentPair(Observation current,
                                              Optional<CorrelationPair> existing,
                                              String desiredPeerObservationId) {
        if (existing.isEmpty()) {
            return;
        }
        CorrelationPair pair = existing.orElseThrow();
        boolean samePeer = pair.observationAId().equals(desiredPeerObservationId)
                || pair.observationBId().equals(desiredPeerObservationId);
        if (!samePeer) {
            ledger.releaseCorrelationPair(current.rawSource(), current.channelMessageId());
        }
    }

    private RecoveryOutcome recoverIntent(IngestIntent pending) {
        Optional<Message> boardMessage = board.findInbound(
                SourceAddress.parse(pending.rawSource()), pending.channelMessageId());
        if (boardMessage.isEmpty()) {
            return new RecoveryOutcome(false, false, true);
        }
        IngestIntent intent = pending;
        if (intent.state() == io.github.c1589190.bma3.app.channel.store
                .QqChannelObservationStore.IngestIntentState.RECORDED) {
            ledger.markIngestIntentBoardCommitted(intent.intentKey(),
                    boardMessage.orElseThrow().localSeq());
        }
        if (intent.state() != io.github.c1589190.bma3.app.channel.store
                .QqChannelObservationStore.IngestIntentState.OBSERVATION_COMMITTED) {
            IngestPromotion promotion = ledger.promoteIngestIntent(
                    intent.intentKey(), boardMessage.orElseThrow().localSeq());
            intent = promotion.intent();
            Observation observation = promotion.observation();
            routeObservation(intent, observation, inputFromObservation(intent, observation), true);
            return new RecoveryOutcome(true, true, false);
        }
        Observation observation = ledger.findObservation(
                        intent.rawSource(), intent.channelMessageId())
                .orElse(null);
        if (observation == null) {
            return new RecoveryOutcome(false, false, true);
        }
        routeObservation(intent, observation, inputFromObservation(intent, observation), true);
        return new RecoveryOutcome(false, true, false);
    }

    private QqChannelRouteInput inputFromObservation(IngestIntent intent, Observation observation) {
        Message message = board.findInbound(SourceAddress.parse(observation.rawSource()),
                        observation.channelMessageId())
                .orElseThrow(() -> new QqChannelRouterException(
                        QqChannelRouterException.Code.RECOVERY_FAILED,
                        "recovery 缺少 board fact"));
        InboxEntry entry = previewFactory.render(observation.channel(), message);
        return new QqChannelRouteInput(false, observation.accountId(),
                message.inbound().source(), message.inbound().messageId(),
                observation.senderId(), message.inbound().direction(), observation.messageAt(),
                observation.observedAt(), observation.priority(), observation.timeReliability(),
                observation.contentIntegrity(), observation.structuredMentionsTrusted(),
                message.inbound().segments(),
                message.inbound(), entry,
                QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
    }

    private record RecoveryOutcome(boolean promoted, boolean routed, boolean skipped) {
    }

    private record CorrelationOutcome(CorrelationState state, Optional<Observation> peer,
                                      boolean truncated) {
        private CorrelationOutcome {
            Objects.requireNonNull(state, "state");
            peer = peer == null ? Optional.empty() : peer;
        }

        static CorrelationOutcome none() {
            return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), false);
        }

        static CorrelationOutcome overflow() {
            return new CorrelationOutcome(CorrelationState.UNVERIFIED, Optional.empty(), true);
        }
    }

    private record WindowScan(List<Observation> items, boolean truncated) {
        private WindowScan {
            items = List.copyOf(items);
        }
    }

    private record SuppressionScan(List<FallbackSuppression.Candidate> candidates,
                                   boolean truncated) {
        private SuppressionScan {
            candidates = List.copyOf(candidates);
        }
    }

    private static QqChannelRouteResult result(IngestIntent intent, Observation observation,
                                                QqChannelRouteResult.Disposition disposition,
                                                String route, String reason,
                                                String canonical, String itemId) {
        return new QqChannelRouteResult(disposition, route, reason, canonical,
                observation.channel().wireName(), itemId, observation.observationId());
    }

    private static String canonicalSource(String threadId) {
        return "qq_thread/" + threadId;
    }

    private static boolean isFallback(IngestIntent intent) {
        return intent.preferredChannel().isPresent()
                && intent.preferredChannelAvailability() == ChannelAvailability.UNAVAILABLE;
    }

    private static boolean correlationNeedsPeer(CorrelationState state) {
        return state == CorrelationState.MATCHED
                || state == CorrelationState.UNVERIFIED_MATCH
                || state == CorrelationState.CONFLICT
                || state == CorrelationState.SUPPRESSED_UNVERIFIED;
    }

    private static CorrelationState mapState(ObservationState state) {
        return switch (state) {
            case MATCHED -> CorrelationState.MATCHED;
            case UNVERIFIED_MATCH -> CorrelationState.UNVERIFIED_MATCH;
            case CONFLICT -> CorrelationState.CONFLICT;
            case CHANNEL_ONLY -> CorrelationState.CHANNEL_ONLY;
            case UNVERIFIED -> CorrelationState.UNVERIFIED;
        };
    }

    private static io.github.c1589190.bma3.app.channel.matching.Channel matcherChannel(Channel channel) {
        return channel == Channel.ONEBOT
                ? io.github.c1589190.bma3.app.channel.matching.Channel.ONEBOT
                : io.github.c1589190.bma3.app.channel.matching.Channel.QQ_OFFICIAL;
    }

    private static ContentIntegrity mapIntegrity(
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity value) {
        return switch (value) {
            case COMPLETE -> ContentIntegrity.COMPLETE;
            case INCOMPLETE -> ContentIntegrity.INCOMPLETE;
            case TRUNCATED -> ContentIntegrity.TRUNCATED;
            case UNKNOWN -> ContentIntegrity.UNKNOWN;
        };
    }

    private static TimeReliability mapTime(
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.TimeReliability value) {
        return switch (value) {
            case RELIABLE -> TimeReliability.RELIABLE;
            case FALLBACK -> TimeReliability.FALLBACK;
            case UNKNOWN -> TimeReliability.UNKNOWN;
        };
    }

    private Duration currentMatchingTolerance() {
        Duration value = matchingToleranceSupplier.get();
        if (value == null
                || value.isNegative()
                || value.compareTo(Duration.ofSeconds(300)) > 0
                || value.getNano() != 0) {
            throw new IllegalArgumentException(
                    "matchingTolerance 必须是 0..300 的整秒 Duration");
        }
        return value;
    }

    private static Supplier<Duration> fixedTolerance(Duration value) {
        Objects.requireNonNull(value, "matchingTolerance");
        if (value.isNegative() || value.compareTo(Duration.ofSeconds(300)) > 0
                || value.getNano() != 0) {
            throw new IllegalArgumentException(
                    "matchingTolerance 必须是 0..300 的整秒 Duration");
        }
        return () -> value;
    }

    private static io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Direction
            storeDirection(io.github.c1589190.bma3.core.message.Direction direction) {
        return direction == io.github.c1589190.bma3.core.message.Direction.INBOUND
                ? io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Direction.INBOUND
                : io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Direction.OUTBOUND;
    }

    private static Channel channelOf(SourceAddress source) {
        return switch (source.platform()) {
            case "qq_group", "qq_private", "qq_system" -> Channel.ONEBOT;
            case "qq_official_group", "qq_official_private", "qq_official_system" ->
                    Channel.QQ_OFFICIAL;
            default -> throw new QqChannelRouterException(
                    QqChannelRouterException.Code.INVALID_INPUT,
                    "不支持的 QQ raw source platform");
        };
    }

    private static SourceView rawSourceView(QqChannelRouteInput input) {
        return input.sourceView() == null
                ? SourceView.of(input.source(), input.source().displayFallback())
                : input.sourceView();
    }

    private static InboxEntry entryFor(QqChannelRouteInput input, Observation observation,
                                       Channel channel) {
        InboxEntry entry = input.inboxEntry();
        if (entry == null) {
            return new InboxEntry(observation.boardLocalSeq(), observation.messageAt(), "",
                    "", List.of(), channel.wireName(), Map.of());
        }
        if (!entry.channel().isEmpty() && !entry.channel().equals(channel.wireName())) {
            throw new QqChannelRouterException(
                    QqChannelRouterException.Code.INVALID_INPUT,
                    "InboxEntry channel 与 observation channel 不一致");
        }
        return new InboxEntry(observation.boardLocalSeq(), entry.at(), entry.sender(),
                entry.text(), entry.markers(), channel.wireName(), entry.evidence());
    }

    private static InboxEntry withLocalSeq(InboxEntry entry, long localSeq, Channel channel) {
        if (entry == null) {
            return new InboxEntry(localSeq, Instant.now(), "", "", List.of(),
                    channel.wireName(), Map.of());
        }
        return new InboxEntry(localSeq, entry.at(), entry.sender(), entry.text(),
                entry.markers(), channel.wireName(), entry.evidence());
    }

    private static Map<String, Object> summary(QqChannelRouteInput input, Observation observation,
                                                String state) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("channel", channelOf(input.source()).wireName());
        summary.put("rawSource", input.source().format());
        summary.put("messageId", input.messageId());
        summary.put("selectionState", state);
        if (observation != null) {
            summary.put("observationId", observation.observationId());
        }
        return Map.copyOf(summary);
    }

    private static String rawRequestKey(QqChannelRouteInput input) {
        return requestKey(input.source().format(), input.messageId());
    }

    private static String requestKey(String rawSource, String messageId) {
        return "qqroute-" + sha256("qq-channel-route-request-v1\0" + rawSource + "\0" + messageId);
    }

    private static String intentKey(String rawSource, String messageId) {
        return "qqintent-" + sha256("qq-channel-ingest-intent-v1\0" + rawSource + "\0" + messageId);
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(value & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** Test seam for crash-point recovery. Production uses {@link CrashHook#noop()}. */
    @FunctionalInterface
    public interface CrashHook {
        void at(CrashPoint point);

        static CrashHook noop() {
            return point -> {
            };
        }
    }

    public enum CrashPoint {
        AFTER_RAW_SUPPRESSION,
        AFTER_INTENT,
        AFTER_BOARD,
        AFTER_BOARD_COMMITTED,
        AFTER_PROMOTION,
        AFTER_CORRELATION,
        AFTER_INBOX_BODY,
        AFTER_DISPLAY_PENDING,
        AFTER_PEER_EVIDENCE_TARGET,
        AFTER_ROUTE_LINK,
        AFTER_DISPLAY_LINKED,
        AFTER_EVIDENCE_ROUTE,
        AFTER_EVIDENCE
    }

}

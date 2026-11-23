package io.github.c1589190.bma3.app.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.channel.router.QqChannelRouteInput;
import io.github.c1589190.bma3.app.channel.router.QqChannelRouteResult;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.priority.PriorityResolver;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountEvent;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEventDecoder;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqSystemEvent;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqUnknownEvent;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceException;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayDispatch;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqIngestTest {

    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final Instant CLOCK_NOW = Instant.parse("2026-11-17T01:02:03Z");
    private static final Set<String> KNOWN = Set.of("bot-a", "bot-b");
    private static final Predicate<String> KNOWN_ACCOUNTS = KNOWN::contains;

    @TempDir
    Path tempDir;

    private Fixture fixture(AppConfig.Weighting weighting, OfficialQqProjectionSink sink) {
        return fixture(weighting, sink, OfficialQqRouteObserver.noop(), Clock.systemUTC(), null);
    }

    private Fixture fixture(AppConfig.Weighting weighting, OfficialQqProjectionSink sink,
                            OfficialQqRouteObserver observer, Clock clock) {
        return fixture(weighting, sink, observer, clock, null);
    }

    private Fixture fixture(AppConfig.Weighting weighting, OfficialQqProjectionSink sink,
                            OfficialQqRouteObserver observer, Clock clock,
                            OfficialQqEventReferenceStore eventReferenceStore) {
        return fixture(weighting, sink, observer, clock, eventReferenceStore, null);
    }

    private Fixture fixture(AppConfig.Weighting weighting, OfficialQqProjectionSink sink,
                            OfficialQqRouteObserver observer, Clock clock,
                            OfficialQqEventReferenceStore eventReferenceStore,
                            OfficialQqIngest.PreBoardIngressHandler preBoardIngressHandler) {
        SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"));
        OfficialQqDirectoryStore directory = new OfficialQqDirectoryStore(
                tempDir.resolve("official-directory.db"));
        SqliteInboxStore inboxStore = new SqliteInboxStore(tempDir.resolve("inbox.db"));
        InboxService inbox = new InboxService(inboxStore,
                new DeliveryPolicy(300, Duration.ofMillis(1), Duration.ofMillis(1)));
        PriorityResolver priorities = new PriorityResolver(() -> weighting);
        OfficialQqIngest.Builder builder = OfficialQqIngest.builder(
                        board, directory, priorities, KNOWN_ACCOUNTS, sink)
                .clock(clock)
                .routeObserver(observer)
                .preBoardIngressHandler(preBoardIngressHandler);
        if (eventReferenceStore != null) {
            builder.eventReferenceStore(eventReferenceStore);
        }
        OfficialQqIngest ingest = builder.build();
        return new Fixture(board, directory, inboxStore, inbox, ingest, eventReferenceStore);
    }

    private OfficialQqEventReferenceStore eventStore(String name) {
        return new OfficialQqEventReferenceStore(tempDir.resolve(name), Clock.fixed(
                T0, java.time.ZoneOffset.UTC));
    }

    private OfficialQqProjectionSink inboxSink(InboxService inbox) {
        return new OfficialInboxProjectionSink(inbox);
    }

    @Test
    void preservesExactStringIdsAndWritesRawOfficialSourceBeforeProjecting() {
        AtomicReference<OfficialQqProjection> seen = new AtomicReference<>();
        OfficialQqProjectionSink capture = projection -> {
            seen.set(projection);
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(defaultWeighting(), capture)) {
            // Deliberately larger than a signed 64-bit id; official ids must never be parsed.
            String messageId = "900719925474099312345678901234567890";
            OfficialQqEvent event = privateText("event-1", messageId, "user_openid_甲", "hello");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user_openid_甲");
            var rows = fixture.board.read(source, 0L, 10);
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.externalMessageId()).isEqualTo(messageId);
                assertThat(row.inbound().messageId()).isEqualTo(messageId);
                assertThat(row.inbound().senderId()).isEqualTo("user_openid_甲");
                assertThat(row.inbound().source().format())
                        .isEqualTo("qq_official_private/bot-a/user_openid_甲");
            });
            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().requestKey()).hasSizeGreaterThan(32);
            assertThat(seen.get().duplicateOnBoard()).isFalse();
            assertThat(seen.get().entry().text()).isEqualTo("hello");
            assertThat(seen.get().entry().channel()).isEqualTo("qq_official");
        }
    }

    @Test
    void groupAtUsesOfficialSignalAndConfiguredPriorityWithoutHardcodedWeights() {
        AtomicReference<OfficialQqProjection> seen = new AtomicReference<>();
        OfficialQqProjectionSink capture = projection -> {
            seen.set(projection);
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        AppConfig.Weighting weighting = weighting(300, -300, 0, 250);
        try (Fixture fixture = fixture(weighting, capture)) {
            OfficialQqEvent event = groupAt("event-g", "message-g", "群昵称", "hello bot");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().priority().priority()).isEqualTo(250);
            assertThat(seen.get().priority().dropped()).isFalse();
            assertThat(seen.get().priority().reasons())
                    .anyMatch(reason -> reason.contains("qq_at_bot +250"));
        }
    }

    @Test
    void mediaAndCredentialPayloadStayInSafeBoardSegmentsAndNeverEnterInboxBody() {
        AtomicReference<OfficialQqProjection> seen = new AtomicReference<>();
        OfficialQqProjectionSink capture = projection -> {
            seen.set(projection);
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(defaultWeighting(), capture)) {
            OfficialQqEvent event = new OfficialQqEvent(
                    "event-media", "C2C_MESSAGE_CREATE", "message-media", "", "user-media",
                    "", "Alice", "", "member", T0,
                    List.of(new Segment("text", "看这个"),
                            new Segment("image", "[图片]", Map.of(
                                    "url", "https://cdn.example/image.png?rkey=rkey-1"
                                            + "&auth_token=must-not-persist",
                                    "rkey", "rkey-1",
                                    "auth_token", "must-not-persist",
                                    "filename", "photo.png"))),
                    "", "trace-media");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user-media");
            var boardSegments = fixture.board.read(source, 0L, 10).getFirst()
                    .inbound().segments();
            assertThat(boardSegments).hasSize(2);
            Segment image = boardSegments.get(1);
            assertThat(image.type()).isEqualTo("image");
            assertThat(image.text()).isEqualTo("[图片]");
            assertThat(image.data("url")).contains("rkey=rkey-1");
            assertThat(image.data("url")).doesNotContain("auth_token");
            assertThat(image.data("rkey")).isEqualTo("rkey-1");
            assertThat(image.data()).doesNotContainKey("auth_token");

            // Board text is derived only from segment.text(); structured media URLs never enter it.
            String boardRenderedText = boardSegments.stream()
                    .map(Segment::text)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            assertThat(boardRenderedText).doesNotContain("https://").doesNotContain("rkey-1");

            assertThat(seen.get().entry().text()).isEqualTo("看这个");
            assertThat(seen.get().entry().markers()).containsExactly("[图片]");
            String inboxRendered = seen.get().entry().render();
            assertThat(inboxRendered).doesNotContain("https://")
                    .doesNotContain("rkey-1")
                    .doesNotContain("must-not-persist");
            assertThat(seen.get().channelSummary().toString())
                    .doesNotContain("https://").doesNotContain("rkey-1");
        }
    }

    @Test
    void invalidAccountSourceAndMessageIdFailClosedBeforeAnyFact() {
        try (Fixture fixture = fixture(defaultWeighting(), inboxSinkReference())) {
            assertThatThrownBy(() -> fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bad account", privateText("e-a", "m-a", "u-a", "body"))))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_ACCOUNT));

            OfficialQqEvent badSource = new OfficialQqEvent(
                    "e-s", "GROUP_AT_MESSAGE_CREATE", "m-s", "bad/group", "u-s",
                    "member-s", "Alice", "card", "member", T0,
                    List.of(new Segment("text", "body")), "", "trace-s");
            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a", badSource)))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_SOURCE));

            // The current OfficialQqEvent constructor rejects whitespace/pipe in messageId too.
            // Exercise the ingest-level guard directly so a future/direct model cannot bypass it.
            assertThatThrownBy(() -> OfficialQqIngest.validateMessageId(
                    "message id with space"))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_MESSAGE_ID));

            // board messageKey is source + '|' + messageId; a direct event model must not be able
            // to introduce the delimiter via account, source target, or message id.
            assertThatThrownBy(() -> fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot|a", privateText("e-pipe-account", "m-pipe-account", "u-a", "body"))))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_ACCOUNT));

            OfficialQqEvent pipeSource = new OfficialQqEvent(
                    "e-pipe-source", "GROUP_AT_MESSAGE_CREATE", "m-pipe-source",
                    "group|openid", "u-pipe", "member-pipe", "Alice", "card", "member", T0,
                    List.of(new Segment("text", "body")), "", "trace-pipe-source");
            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a", pipeSource)))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_SOURCE));

            // The current OfficialQqEvent constructor also rejects pipe in messageId. Exercise the
            // ingest-level guard directly so a future/direct model cannot silently bypass it.
            assertThatThrownBy(() -> OfficialQqIngest.validateMessageId("message|id"))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.INVALID_MESSAGE_ID));

            assertThat(fixture.board.threads(10)).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
        }
    }

    @Test
    void duplicateAndSuccessfulReplayShareRequestKeyButDoNotInjectTwice() {
        List<OfficialQqProjection> projections = new ArrayList<>();
        AtomicReference<OfficialInboxProjectionSink> delegate = new AtomicReference<>();
        OfficialQqProjectionSink recording = projection -> {
            projections.add(projection);
            return delegate.get().project(projection);
        };
        try (Fixture fixture = fixture(defaultWeighting(), recording)) {
            delegate.set(new OfficialInboxProjectionSink(fixture.inbox));
            OfficialQqEvent event = privateText("event-dup", "message-dup", "user-dup", "once");
            OfficialQqAccountEvent accountEvent = new OfficialQqAccountEvent("bot-a", event);
            fixture.ingest.onEvent(accountEvent);
            fixture.ingest.onEvent(accountEvent);

            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user-dup");
            assertThat(fixture.board.read(source, 0L, 10)).hasSize(1);
            assertThat(projections).hasSize(2);
            assertThat(projections.get(0).duplicateOnBoard()).isFalse();
            assertThat(projections.get(1).duplicateOnBoard()).isTrue();
            assertThat(projections.get(1).requestKey())
                    .isEqualTo(projections.get(0).requestKey());
            assertThat(fixture.inboxStore.items()).singleElement()
                    .satisfies(item -> assertThat(item.entries()).hasSize(1));
        }
    }

    @Test
    void postBoardSinkFailurePropagatesAndDuplicateReplayRecoversOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<OfficialInboxProjectionSink> delegate = new AtomicReference<>();
        OfficialQqProjectionSink flaky = projection -> {
            if (calls.incrementAndGet() == 1) {
                throw new java.io.IOException("injected projection failure");
            }
            return delegate.get().project(projection);
        };
        try (Fixture fixture = fixture(defaultWeighting(), flaky,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(),
                eventStore("event-ref-replay.db"))) {
            delegate.set(new OfficialInboxProjectionSink(fixture.inbox));
            OfficialQqEvent event = privateText(
                    "event-fail", "message-fail", "user-fail", "recover me");
            OfficialQqAccountEvent accountEvent = new OfficialQqAccountEvent("bot-a", event);

            assertThatThrownBy(() -> fixture.ingest.onEvent(accountEvent))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.PROJECTION_FAILED));
            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user-fail");
            assertThat(fixture.board.read(source, 0L, 10)).hasSize(1);
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(fixture.eventReferenceStore.count()).isEqualTo(1);
            OfficialQqEventReferenceStore.Reference audit =
                    fixture.eventReferenceStore.find(
                            "bot-a", source, "event-fail").orElseThrow();
            assertThat(audit.messageId()).isEqualTo("message-fail");
            assertThat(audit.replyable()).isFalse();
            assertThatThrownBy(() -> fixture.eventReferenceStore.requireReplyable(
                    "bot-a", source, "event-fail"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.NOT_REPLYABLE));

            fixture.ingest.onEvent(accountEvent);
            fixture.ingest.onEvent(accountEvent);
            assertThat(fixture.board.read(source, 0L, 10)).hasSize(1);
            assertThat(fixture.inboxStore.items()).singleElement()
                    .satisfies(item -> assertThat(item.entries()).hasSize(1));
            assertThat(fixture.eventReferenceStore.count()).isEqualTo(1);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void runtimeSinkFailureIsNotWrappedOrSwallowed() {
        RuntimeException injected = new IllegalStateException("boom");
        OfficialQqProjectionSink failing = projection -> {
            throw injected;
        };
        try (Fixture fixture = fixture(defaultWeighting(), failing)) {
            assertThatThrownBy(() -> fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", privateText("event-rt", "message-rt", "user-rt", "body"))))
                    .isSameAs(injected);
            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user-rt");
            assertThat(fixture.board.read(source, 0L, 10)).hasSize(1);

            // The gateway-facing adapter must also let the original failure escape so the
            // gateway client can mark the dispatch uncommitted and Resume it.
            var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            data.put("id", "message-rt-2");
            data.put("timestamp", T0.toString());
            data.put("content", "body");
            data.putObject("author").put("user_openid", "user-rt").put("username", "Alice");
            GatewayDispatch gatewayDispatch = new GatewayDispatch(
                    "event-rt-2", "C2C_MESSAGE_CREATE", 2L, data, T0);
            assertThatThrownBy(() -> fixture.ingest.gatewaySink(
                    "bot-a", new OfficialQqEventDecoder()).onDispatch(gatewayDispatch))
                    .isSameAs(injected);
        }
    }

    @Test
    void droppedMessageProducesNoBoardDirectoryOrInboxFact() {
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(weighting(300, 100, 0, 0), counting)) {
            OfficialQqEvent event = groupAt("event-drop", "message-drop", "name", "drop");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            SourceAddress source = SourceAddress.parse(
                    "qq_official_group/bot-a/group-openid");
            assertThat(fixture.board.read(source, 0L, 10)).isEmpty();
            assertThat(fixture.directory.groups("bot-a", 10)).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(sinkCalls).hasValue(0);
            assertThat(fixture.ingest.stats().droppedMessages()).isEqualTo(1);
        }
    }

    @Test
    void systemDirectoryEventsAndReceiveSwitchesAreAccountScoped() {
        try (Fixture fixture = fixture(defaultWeighting(), inboxSinkReference())) {
            fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", system("GROUP_ADD_ROBOT", "event-g1", "group-1", "", "群A")));
            assertThat(fixture.directory.group("bot-a", "group-1")).get()
                    .satisfies(group -> {
                        assertThat(group.joined())
                                .isEqualTo(OfficialQqDirectoryStore.State.YES);
                        assertThat(group.receiveMessages())
                                .isEqualTo(OfficialQqDirectoryStore.State.UNKNOWN);
                    });

            fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", system("GROUP_MSG_RECEIVE", "event-g2", "group-1", "", "群A")));
            fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", system("GROUP_MSG_REJECT", "event-g3", "group-1", "", "群A")));
            assertThat(fixture.directory.group("bot-a", "group-1")).get()
                    .extracting(OfficialQqDirectoryStore.Group::receiveMessages)
                    .isEqualTo(OfficialQqDirectoryStore.State.NO);

            fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", system("FRIEND_ADD", "event-f1", "", "friend-1", "好友甲")));
            assertThat(fixture.directory.user("bot-a", "friend-1")).get()
                    .satisfies(user -> {
                        assertThat(user.friend())
                                .isEqualTo(OfficialQqDirectoryStore.State.YES);
                        assertThat(user.name()).isEqualTo("好友甲");
                    });
            fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "bot-a", system("FRIEND_DEL", "event-f2", "", "friend-1", "好友甲")));
            assertThat(fixture.directory.user("bot-a", "friend-1")).get()
                    .extracting(OfficialQqDirectoryStore.User::friend)
                    .isEqualTo(OfficialQqDirectoryStore.State.NO);

            assertThat(fixture.directory.group("bot-b", "group-1")).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
        }
    }

    @Test
    void samePlatformMessageIdOnDifferentAccountsRemainsTwoFacts() {
        List<OfficialQqProjection> projections = new ArrayList<>();
        AtomicReference<OfficialInboxProjectionSink> delegate = new AtomicReference<>();
        OfficialQqProjectionSink recording = projection -> {
            projections.add(projection);
            return delegate.get().project(projection);
        };
        try (Fixture fixture = fixture(defaultWeighting(), recording)) {
            delegate.set(new OfficialInboxProjectionSink(fixture.inbox));
            OfficialQqEvent event = privateText(
                    "event-multi", "shared-message-id", "shared-user", "hello");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-b", event));

            assertThat(fixture.board.read(
                    SourceAddress.parse("qq_official_private/bot-a/shared-user"), 0L, 10))
                    .hasSize(1);
            assertThat(fixture.board.read(
                    SourceAddress.parse("qq_official_private/bot-b/shared-user"), 0L, 10))
                    .hasSize(1);
            assertThat(projections).hasSize(2);
            assertThat(projections.get(0).requestKey())
                    .isNotEqualTo(projections.get(1).requestKey());
            assertThat(fixture.inboxStore.items()).hasSize(2);
        }
    }

    @Test
    void unknownAccountAndUnknownDispatchNeverWriteFacts() {
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.skipped("unknown", "no_projection");
        };
        try (Fixture fixture = fixture(defaultWeighting(), counting)) {
            assertThatThrownBy(() -> fixture.ingest.onEvent(new OfficialQqAccountEvent(
                    "not-configured", privateText("e", "m", "u", "body"))))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialIngestException) failure).code())
                            .isEqualTo(OfficialIngestException.Code.UNKNOWN_ACCOUNT));

            OfficialQqUnknownEvent unknown = new OfficialQqUnknownEvent(
                    "event-unknown", "FUTURE_SECRET auth_token=top-secret", 7, T0, 123);
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", unknown));
            assertThat(fixture.ingest.stats().unknownDispatches()).isEqualTo(1);
            assertThat(sinkCalls).hasValue(0);
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(unknown.toString()).doesNotContain("top-secret");
        }
    }

    @Test
    void routeDecisionHookCanRecoverADuplicateAfterBindingChangesWithoutLosingBoardEvidence() {
        List<OfficialQqProjection> projections = new ArrayList<>();
        List<OfficialQqProjectionSink.Result> decisions = new ArrayList<>();
        AtomicInteger call = new AtomicInteger();
        AtomicReference<OfficialInboxProjectionSink> delegate = new AtomicReference<>();
        OfficialQqProjectionSink adaptive = projection -> {
            projections.add(projection);
            if (call.incrementAndGet() == 1) {
                return OfficialQqProjectionSink.Result.skipped(
                        "source_paused", "injection_disabled");
            }
            return delegate.get().project(projection);
        };
        OfficialQqRouteObserver observer =
                (projection, result) -> decisions.add(result);
        try (Fixture fixture = fixture(defaultWeighting(), adaptive, observer, Clock.systemUTC())) {
            delegate.set(new OfficialInboxProjectionSink(fixture.inbox));
            OfficialQqEvent event = privateText(
                    "event-route", "message-route", "user-route", "recover after bind");
            OfficialQqAccountEvent accountEvent = new OfficialQqAccountEvent("bot-a", event);

            fixture.ingest.onEvent(accountEvent);
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(decisions).singleElement()
                    .extracting(OfficialQqProjectionSink.Result::route)
                    .isEqualTo("source_paused");

            fixture.ingest.onEvent(accountEvent);
            assertThat(projections).hasSize(2);
            assertThat(projections.get(1).duplicateOnBoard()).isTrue();
            assertThat(fixture.inboxStore.items()).singleElement()
                    .satisfies(item -> assertThat(item.entries()).hasSize(1));
            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(1).projected()).isTrue();
        }
    }

    @Test
    void preBoardHandlerRunsBeforeAnyBoardWriteAndKeepsPostHandlerFacts() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-basic.db");
        AtomicReference<SqliteMessageBoardStore> boardRef = new AtomicReference<>();
        AtomicBoolean boardEmptyAtHandler = new AtomicBoolean(false);
        AtomicReference<QqChannelRouteInput> seen = new AtomicReference<>();
        AtomicInteger rawProjectionCalls = new AtomicInteger();
        OfficialQqProjectionSink rawSink = projection -> {
            rawProjectionCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "old_path", projection.source().raw(), "qq_official");
        };
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            boardEmptyAtHandler.set(boardRef.get().threads(10).isEmpty());
            seen.set(input);
            boardRef.get().append(input.boardMessage());
            return routeResult(input);
        };

        Clock fixed = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);
        try (Fixture fixture = fixture(defaultWeighting(), rawSink,
                OfficialQqRouteObserver.noop(), fixed, references, handler)) {
            boardRef.set(fixture.board);
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupAt("event-pre-board", "message-pre-board", "昵称", "hello")));

            assertThat(boardEmptyAtHandler).isTrue();
            QqChannelRouteInput input = seen.get();
            assertThat(input).isNotNull();
            assertThat(input.dropped()).isFalse();
            assertThat(input.accountId()).isEqualTo("bot-a");
            assertThat(input.source().format())
                    .isEqualTo("qq_official_group/bot-a/group-openid");
            assertThat(input.messageId()).isEqualTo("message-pre-board");
            assertThat(input.senderId()).isEqualTo("member-openid");
            assertThat(input.messageAt()).isEqualTo(T0);
            assertThat(input.observedAt()).isEqualTo(CLOCK_NOW);
            assertThat(input.timeReliability()).isEqualTo(TimeReliability.RELIABLE);
            assertThat(input.contentIntegrity()).isEqualTo(ContentIntegrity.INCOMPLETE);
            assertThat(input.structuredMentionsTrusted()).isTrue();
            assertThat(input.segments()).hasSize(2);
            assertThat(input.boardMessage().source()).isEqualTo(input.source());
            assertThat(input.boardMessage().messageId()).isEqualTo("message-pre-board");
            assertThat(input.inboxEntry().localSeq()).isZero();
            assertThat(input.inboxEntry().text()).isEqualTo("hello");
            assertThat(input.inboxEntry().markers()).contains("@bot");
            assertThat(input.inboxEntry().channel()).isEqualTo("qq_official");
            assertThat(input.inboxEntry().evidence())
                    .containsEntry("rawSource", input.source().format())
                    .containsEntry("messageId", "message-pre-board");
            assertThat(input.sourceView()).isNotNull();
            assertThat(input.sourceView().raw()).isEqualTo(input.source().format());
            assertThat(input.sourceView().accountId()).isEqualTo("bot-a");
            assertThat(input.sourceView().label()).isNotBlank();
            assertThat(input.rawProjectionMode())
                    .isEqualTo(QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);

            // The handler owns the board write; the old projection sink is not invoked after it.
            assertThat(fixture.board.read(input.source(), 0L, 10)).hasSize(1);
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(rawProjectionCalls).hasValue(0);
            assertThat(references.find("bot-a", input.source(), "event-pre-board"))
                    .get().satisfies(reference -> {
                        assertThat(reference.messageId()).isEqualTo("message-pre-board");
                        assertThat(reference.replyable()).isFalse();
                    });
            assertThat(fixture.directory.group("bot-a", "group-openid")).isPresent();
        }
    }

    @Test
    void droppedMessageNeverCallsInjectedPreBoardHandlerOrWritesAnything() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-drop.db");
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger rawProjectionCalls = new AtomicInteger();
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            handlerCalls.incrementAndGet();
            return routeResult(input);
        };
        OfficialQqProjectionSink rawSink = projection -> {
            rawProjectionCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "old_path", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(weighting(300, 100, 0, 0), rawSink,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references, handler)) {
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupAt("event-pre-board-drop", "message-pre-board-drop", "name", "drop")));

            assertThat(handlerCalls).hasValue(0);
            assertThat(rawProjectionCalls).hasValue(0);
            assertThat(fixture.board.threads(10)).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(references.count()).isZero();
            assertThat(fixture.directory.groups("bot-a", 10)).isEmpty();
        }
    }

    @Test
    void preBoardHandlerFailurePropagatesWithoutFallbackWrites() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-fail.db");
        RuntimeException injected = new IllegalStateException("router provider failure");
        OfficialQqProjectionSink rawSink = projection ->
                OfficialQqProjectionSink.Result.projected(
                        "raw", "old_path", projection.source().raw(), "qq_official");
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            throw injected;
        };
        try (Fixture fixture = fixture(defaultWeighting(), rawSink,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references, handler)) {
            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a",
                            groupAt("event-pre-board-fail", "message-pre-board-fail",
                                    "name", "fail"))))
                    .isSameAs(injected);

            assertThat(fixture.board.threads(10)).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(references.count()).isZero();
            assertThat(fixture.directory.groups("bot-a", 10)).isEmpty();
        }
    }

    @Test
    void handlerCannotFakeSuccessWithoutAPersistedInboundBoardFact() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-no-fact.db");
        AtomicInteger handlerCalls = new AtomicInteger();
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            handlerCalls.incrementAndGet();
            return routeResult(input);
        };
        try (Fixture fixture = fixture(defaultWeighting(), inboxSinkReference(),
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references, handler)) {
            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a",
                            groupAt("event-pre-board-no-fact", "message-pre-board-no-fact",
                                    "name", "no fact"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未找到入站消息事实");

            assertThat(handlerCalls).hasValue(1);
            assertThat(fixture.board.threads(10)).isEmpty();
            assertThat(fixture.inboxStore.items()).isEmpty();
            assertThat(references.count()).isZero();
            assertThat(fixture.directory.groups("bot-a", 10)).isEmpty();
        }
    }

    @Test
    void replayThroughPreBoardHandlerIsIdempotentForBoardInboxRefsAndDirectory() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-replay.db");
        AtomicReference<SqliteMessageBoardStore> boardRef = new AtomicReference<>();
        AtomicReference<InboxService> inboxRef = new AtomicReference<>();
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger rawProjectionCalls = new AtomicInteger();
        OfficialQqProjectionSink rawSink = projection -> {
            rawProjectionCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "old_path", projection.source().raw(), "qq_official");
        };
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            handlerCalls.incrementAndGet();
            var appended = boardRef.get().append(input.boardMessage());
            InboxEntry entry = new InboxEntry(
                    appended.message().localSeq(),
                    input.inboxEntry().at(),
                    input.inboxEntry().sender(),
                    input.inboxEntry().text(),
                    input.inboxEntry().markers(),
                    input.inboxEntry().channel(),
                    input.inboxEntry().evidence());
            inboxRef.get().appendProjectedOnce(
                    "pre-board:" + input.source().format() + ":" + input.messageId(),
                    AppendItem.projected(input.sourceView(), entry, input.priority(),
                            input.observedAt(), "qq_official",
                            Map.of("rawSource", input.source().format())));
            return routeResult(input);
        };

        try (Fixture fixture = fixture(defaultWeighting(), rawSink,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references, handler)) {
            boardRef.set(fixture.board);
            inboxRef.set(fixture.inbox);
            OfficialQqEvent event = groupAt(
                    "event-pre-board-replay", "message-pre-board-replay", "name", "once");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            assertThat(handlerCalls).hasValue(2);
            assertThat(rawProjectionCalls).hasValue(0);
            SourceAddress source = SourceAddress.parse(
                    "qq_official_group/bot-a/group-openid");
            assertThat(fixture.board.read(source, 0L, 10)).hasSize(1);
            assertThat(fixture.inboxStore.items()).singleElement()
                    .satisfies(item -> assertThat(item.entries()).hasSize(1));
            assertThat(references.count()).isEqualTo(1);
            assertThat(references.find("bot-a", source, "event-pre-board-replay"))
                    .get().satisfies(reference ->
                            assertThat(reference.messageId()).isEqualTo("message-pre-board-replay"));
            assertThat(fixture.directory.groups("bot-a", 10)).hasSize(1);
        }
    }

    @Test
    void routeInputTrustFlagsAreStrictAndNeverInventReliability() {
        OfficialQqEventReferenceStore references = eventStore("pre-board-trust.db");
        AtomicReference<SqliteMessageBoardStore> boardRef = new AtomicReference<>();
        Map<String, QqChannelRouteInput> inputs = new LinkedHashMap<>();
        OfficialQqIngest.PreBoardIngressHandler handler = input -> {
            inputs.put(input.messageId(), input);
            boardRef.get().append(input.boardMessage());
            return routeResult(input);
        };
        Clock fixed = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);
        try (Fixture fixture = fixture(defaultWeighting(), inboxSinkReference(),
                OfficialQqRouteObserver.noop(), fixed, references, handler)) {
            boardRef.set(fixture.board);
            List<Segment> atBot = List.of(new Segment("text", "hi"),
                    new Segment("at", "@bot", Map.of("is_you", "true")));
            List<Segment> atOther = List.of(new Segment("text", "hi"),
                    new Segment("at", "@other", Map.of("openid", "other-openid")));
            List<Segment> atAll = List.of(new Segment("text", "hi"),
                    new Segment("at", "@all", Map.of("scope", "all", "is_you", "true")));
            List<Segment> noAt = List.of(new Segment("text", "no mention"));

            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-at-bot", "msg-at-bot", atBot,
                            true, false, false, false)));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-at-other", "msg-at-other", atOther,
                            false, false, false, false)));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-at-all", "msg-at-all", atAll,
                            true, true, false, false)));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-no-at", "msg-no-at", noAt,
                            false, false, false, false)));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-fallback", "msg-fallback", noAt,
                            false, false, true, false)));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupEvent("event-complete", "msg-complete", noAt,
                            false, false, false, true)));

            assertThat(inputs.get("msg-at-bot").structuredMentionsTrusted()).isTrue();
            assertThat(inputs.get("msg-at-other").structuredMentionsTrusted()).isFalse();
            assertThat(inputs.get("msg-at-all").structuredMentionsTrusted()).isFalse();
            assertThat(inputs.get("msg-no-at").structuredMentionsTrusted()).isFalse();
            assertThat(inputs.get("msg-at-bot").timeReliability())
                    .isEqualTo(TimeReliability.RELIABLE);
            assertThat(inputs.get("msg-at-bot").contentIntegrity())
                    .isEqualTo(ContentIntegrity.INCOMPLETE);
            assertThat(inputs.get("msg-fallback").timeReliability())
                    .isEqualTo(TimeReliability.FALLBACK);
            assertThat(inputs.get("msg-fallback").contentIntegrity())
                    .isEqualTo(ContentIntegrity.INCOMPLETE);
            assertThat(inputs.get("msg-complete").timeReliability())
                    .isEqualTo(TimeReliability.RELIABLE);
            assertThat(inputs.get("msg-complete").contentIntegrity())
                    .isEqualTo(ContentIntegrity.COMPLETE);
        }
    }

    @Test
    void droppedMessageDoesNotCreateReplyableEventReference() {
        OfficialQqEventReferenceStore references = eventStore("event-ref-drop.db");
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(weighting(300, 100, 0, 0), counting,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references)) {
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupAt("event-drop-ref", "message-drop-ref", "name", "drop")));

            assertThat(fixture.board.read(SourceAddress.parse(
                    "qq_official_group/bot-a/group-openid"), 0L, 10)).isEmpty();
            assertThat(fixture.eventReferenceStore.count()).isZero();
            assertThat(sinkCalls).hasValue(0);
        }
    }

    @Test
    void messageReferenceUsesExactAccountAndSourceAndRejectsCrossSource() {
        OfficialQqEventReferenceStore references = eventStore("event-ref-message.db");
        AtomicReference<OfficialQqProjectionSink> delegate = new AtomicReference<>();
        OfficialQqProjectionSink sink = projection -> delegate.get().project(projection);
        try (Fixture fixture = fixture(defaultWeighting(), sink,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references)) {
            delegate.set(new OfficialInboxProjectionSink(fixture.inbox));
            OfficialQqEvent event = groupAt(
                    "event-ref-message", "message-ref", "name", "remember me");
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            assertThat(references.count()).isEqualTo(1);
            OfficialQqEventReferenceStore.Reference stored = references.find(
                    "bot-a",
                    SourceAddress.parse("qq_official_group/bot-a/group-openid"),
                    "event-ref-message").orElseThrow();
            assertThat(stored.source()).isEqualTo(
                    "qq_official_group/bot-a/group-openid");
            assertThat(stored.messageId()).isEqualTo("message-ref");
            assertThat(stored.eventType()).isEqualTo("GROUP_AT_MESSAGE_CREATE");
            assertThat(stored.replyable()).isFalse();
            assertThatThrownBy(() -> references.requireReplyable(
                    "bot-a",
                    SourceAddress.parse("qq_official_group/bot-a/group-openid"),
                    "event-ref-message"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.NOT_REPLYABLE));

            assertThatThrownBy(() -> references.requireReplyable(
                    "bot-a",
                    SourceAddress.parse("qq_official_group/bot-a/other-group"),
                    "event-ref-message"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.SOURCE_MISMATCH));

            assertThatThrownBy(() -> references.requireReplyable(
                    "bot-b",
                    SourceAddress.parse("qq_official_group/bot-b/group-openid"),
                    "event-ref-message"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.ACCOUNT_MISMATCH));

            // A board-success message without an envelope event id must not fabricate one.
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    groupAt("", "message-without-event-id", "name", "no event id")));
            assertThat(references.count()).isEqualTo(1);

            // Same event envelope id is isolated by account and may be observed on bot-b too.
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-b", event));
            assertThat(references.count()).isEqualTo(2);
            OfficialQqEventReferenceStore.Reference botBAudit = references.find(
                    "bot-b",
                    SourceAddress.parse("qq_official_group/bot-b/group-openid"),
                    "event-ref-message").orElseThrow();
            assertThat(botBAudit.messageId()).isEqualTo("message-ref");
            assertThat(botBAudit.replyable()).isFalse();
        }
    }

    @Test
    void systemEventReferencesAreRecordedOnlyForKnownSourceAndEventId() {
        OfficialQqEventReferenceStore references = eventStore("event-ref-system.db");
        OfficialQqProjectionSink noProjection = projection ->
                OfficialQqProjectionSink.Result.skipped("system", "not_applicable");
        try (Fixture fixture = fixture(defaultWeighting(), noProjection,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references)) {
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    system("GROUP_ADD_ROBOT", "event-system-group",
                            "group-openid", "", "群")));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    system("FRIEND_ADD", "event-system-friend",
                            "", "user-openid", "好友")));

            assertThat(references.requireReplyable(
                    "bot-a",
                    SourceAddress.parse("qq_official_group/bot-a/group-openid"),
                    "event-system-group").eventType()).isEqualTo("GROUP_ADD_ROBOT");
            assertThat(references.requireReplyable(
                    "bot-a",
                    SourceAddress.parse("qq_official_private/bot-a/user-openid"),
                    "event-system-friend").eventType()).isEqualTo("FRIEND_ADD");

            // READY has no raw source; empty event ids and unsupported system types are skipped
            // rather than fabricated. Directory processing remains independent.
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    system("READY", "event-ready", "", "", "")));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    system("GROUP_ADD_ROBOT", "", "group-openid", "", "群")));
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a",
                    system("FUTURE_SYSTEM", "event-future", "group-openid", "", "未来")));

            assertThat(references.count()).isEqualTo(2);
            assertThat(references.find(
                    "bot-a",
                    SourceAddress.parse("qq_official_group/bot-a/group-openid"),
                    "event-ready")).isEmpty();
        }
    }

    @Test
    void eventReferenceStoreFailureAfterBoardWritePropagatesAndSkipsProjection() {
        OfficialQqEventReferenceStore references = eventStore("event-ref-conflict.db");
        references.recordMessage("bot-a",
                groupAt("event-conflict", "stored-message", "name", "original"));
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(defaultWeighting(), counting,
                OfficialQqRouteObserver.noop(), Clock.systemUTC(), references)) {
            OfficialQqEvent incoming = groupAt(
                    "event-conflict", "incoming-message", "name", "new");
            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a", incoming)))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code
                                    .EVENT_REFERENCE_CONFLICT));

            // Board is the fact source and was already committed; projection must not run until a
            // successful replay can reconcile the event reference.
            assertThat(fixture.board.read(SourceAddress.parse(
                    "qq_official_group/bot-a/group-openid"), 0L, 10)).hasSize(1);
            assertThat(sinkCalls).hasValue(0);
            assertThat(references.count()).isEqualTo(1);
        }
    }

    @Test
    void oversizedSegmentCollectionsFailClosedBeforeBoardAndDoNotLeakBody() {
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(defaultWeighting(), counting)) {
            List<Segment> tooMany = new ArrayList<>();
            for (int index = 0; index <= 512; index++) {
                tooMany.add(new Segment("text", "part-" + index));
            }
            OfficialQqEvent event = new OfficialQqEvent(
                    "event-too-many", "C2C_MESSAGE_CREATE", "message-too-many", "",
                    "user-too-many", "", "Alice", "", "member", T0,
                    tooMany, "", "trace-too-many");
            String body = "BODY-MUST-NOT-LEAK-INTO-ERROR";

            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a", event)))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> {
                        OfficialIngestException ingestFailure =
                                (OfficialIngestException) failure;
                        assertThat(ingestFailure.code())
                                .isEqualTo(OfficialIngestException.Code.INVALID_SEGMENT);
                        assertThat(ingestFailure.getMessage()).doesNotContain(body);
                        assertThat(ingestFailure.toString()).doesNotContain(body);
                        assertThat(ingestFailure.getCause()).isNull();
                    });

            assertThat(fixture.board.read(SourceAddress.parse(
                    "qq_official_private/bot-a/user-too-many"), 0L, 10)).isEmpty();
            assertThat(sinkCalls).hasValue(0);
            assertThat(fixture.inboxStore.items()).isEmpty();
        }
    }

    @Test
    void oversizedStructuredValueFailsClosedInsteadOfBeingSilentlyTruncated() {
        AtomicInteger sinkCalls = new AtomicInteger();
        OfficialQqProjectionSink counting = projection -> {
            sinkCalls.incrementAndGet();
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        try (Fixture fixture = fixture(defaultWeighting(), counting)) {
            String huge = "x".repeat(17_000);
            OfficialQqEvent event = new OfficialQqEvent(
                    "event-huge-data", "C2C_MESSAGE_CREATE", "message-huge-data", "",
                    "user-huge-data", "", "Alice", "", "member", T0,
                    List.of(new Segment("text", "normal"),
                            new Segment("image", "[图片]", Map.of(
                                    "url", "https://cdn.example/image.png",
                                    "rkey", huge))),
                    "", "trace-huge-data");

            assertThatThrownBy(() -> fixture.ingest.onEvent(
                    new OfficialQqAccountEvent("bot-a", event)))
                    .isInstanceOf(OfficialIngestException.class)
                    .satisfies(failure -> {
                        OfficialIngestException ingestFailure =
                                (OfficialIngestException) failure;
                        assertThat(ingestFailure.code())
                                .isEqualTo(OfficialIngestException.Code.INVALID_SEGMENT);
                        assertThat(ingestFailure.getMessage()).doesNotContain(huge);
                        assertThat(ingestFailure.toString()).doesNotContain(huge);
                        assertThat(ingestFailure.getCause()).isNull();
                    });

            assertThat(fixture.board.read(SourceAddress.parse(
                    "qq_official_private/bot-a/user-huge-data"), 0L, 10)).isEmpty();
            assertThat(sinkCalls).hasValue(0);
            assertThat(fixture.inboxStore.items()).isEmpty();
        }
    }

    @Test
    void originalTimestampIsPersistedAndAvailabilityUsesInjectedClock() {
        AtomicReference<OfficialQqProjection> seen = new AtomicReference<>();
        OfficialQqProjectionSink capture = projection -> {
            seen.set(projection);
            return OfficialQqProjectionSink.Result.projected(
                    "raw", "capture", projection.source().raw(), "qq_official");
        };
        Instant eventAt = Instant.parse("2020-01-02T03:04:05Z");
        Clock fixed = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);
        try (Fixture fixture = fixture(defaultWeighting(), capture,
                OfficialQqRouteObserver.noop(), fixed)) {
            OfficialQqEvent event = new OfficialQqEvent(
                    "event-time", "C2C_MESSAGE_CREATE", "message-time", "", "user-time",
                    "", "Alice", "", "member", eventAt,
                    List.of(new Segment("text", "timestamp")), "", "trace-time",
                    "", false, false, true, 9L, eventAt);
            fixture.ingest.onEvent(new OfficialQqAccountEvent("bot-a", event));

            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/bot-a/user-time");
            assertThat(fixture.board.read(source, 0L, 10).getFirst().inbound().at())
                    .isEqualTo(eventAt);
            assertThat(seen.get().availableAt()).isEqualTo(CLOCK_NOW);
        }
    }

    private OfficialQqProjectionSink inboxSinkReference() {
        return projection -> OfficialQqProjectionSink.Result.projected(
                "raw", "test", projection.source().raw(), "qq_official");
    }

    private static AppConfig.Weighting defaultWeighting() {
        return weighting(300, -300, 0, 100);
    }

    private static AppConfig.Weighting weighting(int immediateThreshold, int dropBelow,
                                                  int groupBase, int atBotWeight) {
        return new AppConfig.Weighting(
                immediateThreshold,
                dropBelow,
                1000,
                30,
                1,
                Map.of(
                        "*", 0,
                        "qq_official_group", groupBase,
                        "qq_official_private", 300),
                Map.of(
                        "qq_at_bot", atBotWeight,
                        "qq_at_all", 20,
                        "qq_at_other", 0,
                        "qq_reply_to_bot", 100,
                        "qq_self_name", 60,
                        "qq_command", 100),
                List.of(),
                List.of("/", "!"));
    }

    private static QqChannelRouteResult routeResult(QqChannelRouteInput input) {
        return new QqChannelRouteResult(
                QqChannelRouteResult.Disposition.UNBOUND_RAW,
                "raw",
                "pre_board_test",
                input.source().format(),
                "qq_official",
                "",
                "");
    }

    private static OfficialQqEvent groupEvent(String eventId, String messageId,
                                              List<Segment> segments,
                                              boolean atBot, boolean atAll,
                                              boolean timestampFallback,
                                              boolean contentComplete) {
        return new OfficialQqEvent(
                eventId,
                "GROUP_AT_MESSAGE_CREATE",
                messageId,
                "group-openid",
                "user-openid",
                "member-openid",
                "Alice",
                "昵称",
                "member",
                T0,
                segments,
                "",
                "trace-" + eventId,
                "",
                atBot,
                atAll,
                timestampFallback,
                11L,
                T0,
                contentComplete);
    }

    private static OfficialQqEvent privateText(String eventId, String messageId,
                                               String userOpenid, String text) {
        return new OfficialQqEvent(
                eventId, "C2C_MESSAGE_CREATE", messageId, "", userOpenid, "",
                "Alice", "", "member", T0,
                List.of(new Segment("text", text)), "", "trace-" + eventId);
    }

    private static OfficialQqEvent groupAt(String eventId, String messageId,
                                           String nickname, String text) {
        return new OfficialQqEvent(
                eventId,
                "GROUP_AT_MESSAGE_CREATE",
                messageId,
                "group-openid",
                "user-openid",
                "member-openid",
                "Alice",
                nickname,
                "member",
                T0,
                List.of(new Segment("text", text),
                        new Segment("at", "@bot", Map.of("is_you", "true"))),
                "",
                "trace-" + eventId,
                "",
                true,
                false,
                false,
                11L,
                T0);
    }

    private static OfficialQqSystemEvent system(String type, String eventId,
                                                String groupOpenid, String userOpenid,
                                                String displayName) {
        return new OfficialQqSystemEvent(
                eventId, type, 1L, T0, T0, false,
                groupOpenid, userOpenid, "", "", displayName, displayName, "", "");
    }

    private record Fixture(SqliteMessageBoardStore board,
                           OfficialQqDirectoryStore directory,
                           SqliteInboxStore inboxStore,
                           InboxService inbox,
                           OfficialQqIngest ingest,
                           OfficialQqEventReferenceStore eventReferenceStore)
            implements AutoCloseable {
        @Override
        public void close() {
            inbox.close();
            inboxStore.close();
            if (eventReferenceStore != null) {
                eventReferenceStore.close();
            }
            directory.close();
            board.close();
        }
    }
}

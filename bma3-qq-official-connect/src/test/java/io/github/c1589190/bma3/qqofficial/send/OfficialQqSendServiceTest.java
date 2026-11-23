package io.github.c1589190.bma3.qqofficial.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.message.TextSendPolicy;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.core.store.ThreadSummary;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqErrorKind;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqSendServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final SourceAddress GROUP_A =
            SourceAddress.parse("qq_official_group/acc-a/group-openid-A");
    private static final SourceAddress PRIVATE_A =
            SourceAddress.parse("qq_official_private/acc-a/user-openid-A");

    @TempDir
    Path temp;

    @Test
    void messageDiagnosticsNeverContainBodyOrIds() {
        OfficialQqSendMessage message =
                new OfficialQqSendMessage("secret body", "ROBOT1.0_SECRET_MSG", "", 7);

        assertThat(message.toString())
                .doesNotContain("secret body")
                .doesNotContain("ROBOT1.0_SECRET_MSG")
                .contains("contentLength=11")
                .contains("msgSeq=7");
    }

    @Test
    void persistsPendingBeforeDispatchAndFinalizesSentWithExternalId() throws Exception {
        try (SqliteMessageBoardStore board = board("success.db");
             OfficialQqReplySequenceStore seq = seq("success-seq.db")) {
            FakeTransport transport = new FakeTransport("ext-1");
            List<List<Message>> observed = new ArrayList<>();
            transport.onCall = call -> observed.add(board.read(GROUP_A, 0, 20));
            OfficialQqSendService service = service(board, seq, transport, () -> TextSendPolicy.defaults());

            List<OfficialQqSendOutcome> outcomes = service.sendText("acc-a", GROUP_A, "hello");

            assertThat(transport.calls).hasSize(1);
            assertThat(observed).singleElement().satisfies(rows ->
                    assertThat(rows).singleElement().satisfies(row -> {
                        assertThat(row.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
                        assertThat(row.delivery().partIndex()).isEqualTo(1);
                        assertThat(row.delivery().partTotal()).isEqualTo(1);
                    }));
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT);
                assertThat(outcome.externalMessageId()).isEqualTo("ext-1");
                assertThat(outcome.recorded()).isTrue();
                assertThat(outcome.localSeq()).isNotNull();
            });
            assertThat(onlyRow(board, GROUP_A).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(onlyRow(board, GROUP_A).externalMessageId()).isEqualTo("ext-1");
        }
    }

    @Test
    void enforces3000CodepointsAndHonorsRejectThenSplit() throws Exception {
        try (SqliteMessageBoardStore board = board("limit.db");
             OfficialQqReplySequenceStore seq = seq("limit-seq.db")) {
            FakeTransport transport = new FakeTransport();
            var policy = new java.util.concurrent.atomic.AtomicReference<>(TextSendPolicy.defaults());
            OfficialQqSendService service = service(board, seq, transport, policy::get);

            transport.results.add("exact");
            assertThat(service.sendText("acc-a", GROUP_A, "😀".repeat(3000)))
                    .singleElement().satisfies(outcome ->
                            assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT));

            assertThatThrownBy(() -> service.sendText("acc-a", GROUP_A, "😀".repeat(3001)))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TEXT_TOO_LONG"));
            assertThat(transport.calls).hasSize(1);

            policy.set(TextSendPolicy.from(3000, "split"));
            transport.results.add("part-1");
            transport.results.add("part-2");
            List<OfficialQqSendOutcome> split =
                    service.sendText("acc-a", GROUP_A, "😀".repeat(3001));

            assertThat(split).hasSize(2);
            assertThat(split).allSatisfy(outcome ->
                    assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT));
            assertThat(transport.calls).hasSize(3);
            assertThat(transport.calls.get(1).message().content().codePointCount(
                    0, transport.calls.get(1).message().content().length())).isEqualTo(3000);
            assertThat(transport.calls.get(2).message().content()).isEqualTo("😀");
            assertThat(transport.calls.get(1).message().content())
                    .doesNotEndWith("\uD83D");
        }
    }

    @Test
    void rejectsUnknownDisabledCrossAccountAndWrongPlatformBeforeAnyDispatch() throws Exception {
        try (SqliteMessageBoardStore board = board("accounts.db");
             OfficialQqReplySequenceStore seq = seq("accounts-seq.db")) {
            FakeTransport transport = new FakeTransport();
            OfficialQqSendAccounts accounts = accountId -> switch (accountId) {
                case "acc-a" -> Optional.of(new OfficialQqSendAccount("acc-a", true, transport));
                case "acc-disabled" -> Optional.of(
                        new OfficialQqSendAccount("acc-disabled", false, null));
                default -> Optional.empty();
            };
            OfficialQqSendService service = new OfficialQqSendService(
                    board, accounts, TextSendPolicy::defaults, seq, Clock.fixed(T0, ZoneOffset.UTC));

            assertThatThrownBy(() -> service.sendText(
                    "acc-a", SourceAddress.parse("qq_official_group/acc-b/group"), "x"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("SOURCE_ACCOUNT_MISMATCH"));
            assertThatThrownBy(() -> service.sendText(
                    "acc-disabled", SourceAddress.parse("qq_official_group/acc-disabled/group"), "x"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code()).isEqualTo("ACCOUNT_DISABLED"));
            assertThatThrownBy(() -> service.sendText("acc-missing", GROUP_A, "x"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code()).isEqualTo("SOURCE_ACCOUNT_MISMATCH"));
            assertThatThrownBy(() -> service.sendText(
                    "acc-a", SourceAddress.parse("qq_group/acc-a/123"), "x"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code()).isEqualTo("INVALID_SOURCE"));
            assertThatThrownBy(() -> service.sendText(" acc-a", GROUP_A, "x"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code()).isEqualTo("INVALID_ACCOUNT_ID"));

            assertThat(transport.calls).isEmpty();
            assertThat(board.read(GROUP_A, 0, 10)).isEmpty();
        }
    }

    @Test
    void accountAndTransportMustBeBoundToTheSameIdBeforeAnyBoardRow() throws Exception {
        try (SqliteMessageBoardStore board = board("binding.db");
             OfficialQqReplySequenceStore seq = seq("binding-seq.db")) {
            FakeTransport transportForB = new FakeTransport("should-not-send");
            transportForB.accountId = "acc-b";

            assertThatThrownBy(() ->
                    new OfficialQqSendAccount("acc-a", true, transportForB))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(transportForB.calls).isEmpty();
            assertThat(board.read(GROUP_A, 0, 10)).isEmpty();

            FakeTransport transportForA = new FakeTransport("id");
            transportForA.accountId = "acc-b";
            OfficialQqSendAccounts mismatched = accountId ->
                    "acc-a".equals(accountId)
                            ? Optional.of(new OfficialQqSendAccount(
                                    "acc-b", true, transportForA))
                            : Optional.empty();
            OfficialQqSendService service = new OfficialQqSendService(
                    board, mismatched, TextSendPolicy::defaults, seq,
                    Clock.fixed(T0, ZoneOffset.UTC));
            assertThatThrownBy(() -> service.sendText("acc-a", GROUP_A, "hello"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("ACCOUNT_MISMATCH"));
            assertThat(transportForA.calls).isEmpty();
            assertThat(board.read(GROUP_A, 0, 10)).isEmpty();
        }
    }

    @Test
    void typedNotDispatchedIsFailedBeforeSendWhileTypedUnknownStaysUnknown() throws Exception {
        try (SqliteMessageBoardStore board = board("typed.db");
             OfficialQqReplySequenceStore seq = seq("typed-seq.db")) {
            FakeTransport notDispatched = new FakeTransport(
                    OfficialQqTransportException.notDispatched("INVALID_REQUEST"));
            OfficialQqSendService service = service(board, seq, notDispatched,
                    () -> TextSendPolicy.defaults());

            List<OfficialQqSendOutcome> local = service.sendText("acc-a", GROUP_A, "hello");
            assertThat(local).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
                assertThat(outcome.errorCode()).isEqualTo("INVALID_REQUEST");
                assertThat(outcome.dispatched()).isFalse();
            });

            FakeTransport ambiguous = new FakeTransport(
                    OfficialQqTransportException.unknown("MALFORMED_RESPONSE"));
            OfficialQqSendService second = service(board, seq, ambiguous,
                    () -> TextSendPolicy.defaults());
            List<OfficialQqSendOutcome> unknown = second.sendText("acc-a", GROUP_A, "hello");
            assertThat(unknown).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.errorCode()).isEqualTo("MALFORMED_RESPONSE");
                assertThat(outcome.dispatched()).isTrue();
            });

            FakeTransport blankId = new FakeTransport("");
            OfficialQqSendService third = service(board, seq, blankId,
                    () -> TextSendPolicy.defaults());
            List<OfficialQqSendOutcome> blank = third.sendText("acc-a", GROUP_A, "hello");
            assertThat(blank).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.errorCode()).isEqualTo("MALFORMED_RESPONSE");
                assertThat(outcome.dispatched()).isTrue();
            });
        }
    }

    @Test
    void timeoutAndProviderFailuresBecomeUnknownAndAreNeverRetried() throws Exception {
        try (SqliteMessageBoardStore board = board("failures.db");
             OfficialQqReplySequenceStore seq = seq("failures-seq.db")) {
            FakeTransport transport = new FakeTransport(new TimeoutException("raw timeout secret"));
            OfficialQqSendService service = service(board, seq, transport,
                    () -> TextSendPolicy.defaults());

            List<OfficialQqSendOutcome> outcomes = service.sendText("acc-a", GROUP_A, "secret body");

            assertThat(transport.calls).hasSize(1);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.errorCode()).isEqualTo(OfficialQqSendService.TIMEOUT);
                assertThat(outcome.recorded()).isTrue();
                assertThat(outcome.json().toString()).doesNotContain("secret");
            });
            assertThat(onlyRow(board, GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(onlyRow(board, GROUP_A).delivery().errorCode())
                    .isEqualTo(OfficialQqSendService.TIMEOUT);

            transport.results.add(new OfficialQqApiException(
                    OfficialQqErrorKind.PERMISSION, 403, "acc-a", "GROUP_MESSAGES",
                    "11253", "trace-safe", false, "safe detail", null));
            List<OfficialQqSendOutcome> rejected =
                    service.sendText("acc-a", GROUP_A, "another body");
            assertThat(transport.calls).hasSize(2);
            assertThat(rejected).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.errorCode()).isEqualTo("PERMISSION");
                assertThat(outcome.json().toString()).doesNotContain("another body");
            });
        }
    }

    @Test
    void boardFinishFailureIsUnknownWithProviderStateAndNoBlindResend() throws Exception {
        try (SqliteMessageBoardStore board = board("board-fail.db");
             OfficialQqReplySequenceStore seq = seq("board-fail-seq.db")) {
            FakeTransport transport = new FakeTransport("platform-id");
            MessageBoardStore failing = new FailFinishBoard(board);
            OfficialQqSendService service = service(failing, seq, transport,
                    () -> TextSendPolicy.defaults());

            List<OfficialQqSendOutcome> outcomes = service.sendText("acc-a", GROUP_A, "hello");

            assertThat(transport.calls).hasSize(1);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.providerState()).isEqualTo(DeliveryStatus.SENT);
                assertThat(outcome.errorCode()).isEqualTo(OfficialQqSendService.BOARD_WRITE_FAILED);
                assertThat(outcome.recorded()).isFalse();
                assertThat(outcome.externalMessageId()).isEmpty();
                assertThat(outcome.json().path("resendAllowed").asBoolean()).isFalse();
            });
            assertThat(onlyRow(board, GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.PENDING);
        }
    }

    @Test
    void multiPartSendStopsAtFirstNonSuccess() throws Exception {
        try (SqliteMessageBoardStore board = board("multi.db");
             OfficialQqReplySequenceStore seq = seq("multi-seq.db")) {
            FakeTransport transport = new FakeTransport("part-1", new TimeoutException("stop"), "part-3");
            OfficialQqSendService service = service(board, seq, transport,
                    () -> TextSendPolicy.from(3, "split"));

            List<OfficialQqSendOutcome> outcomes = service.sendText("acc-a", GROUP_A, "abcdef");

            assertThat(transport.calls).hasSize(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(outcomes.get(1).status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(board.read(GROUP_A, 0, 10)).hasSize(2);
        }
    }

    @Test
    void passiveReplyValidatesSameSourceAndConsumesDurableSequenceEvenWhenSendFails()
            throws Exception {
        try (SqliteMessageBoardStore board = board("reply.db");
             OfficialQqReplySequenceStore seq = seq("reply-seq.db")) {
            appendInbound(board, GROUP_A, "ROBOT1.0_INBOUND");
            FakeTransport transport = new FakeTransport(
                    "reply-1", new TimeoutException("ambiguous"), "reply-3");
            OfficialQqSendService service = service(board, seq, transport,
                    () -> TextSendPolicy.defaults());

            service.reply("acc-a", GROUP_A, "one", "ROBOT1.0_INBOUND");
            service.reply("acc-a", GROUP_A, "two", "ROBOT1.0_INBOUND");
            List<OfficialQqSendOutcome> third =
                    service.reply("acc-a", GROUP_A, "three", "ROBOT1.0_INBOUND");

            assertThat(transport.calls).hasSize(3);
            assertThat(transport.calls).extracting(call -> call.message().msgSeq())
                    .containsExactly(1, 2, 3);
            assertThat(transport.calls).allSatisfy(call ->
                    assertThat(call.message().msgId()).isEqualTo("ROBOT1.0_INBOUND"));
            assertThat(third).singleElement().satisfies(outcome ->
                    assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT));
            List<Message> outbound = board.read(GROUP_A, 0, 20).stream()
                    .filter(message -> message.inbound().direction() == Direction.OUTBOUND)
                    .toList();
            assertThat(outbound).hasSize(3);
            assertThat(seq.attempt(outbound.get(0).localSeq())).get()
                    .satisfies(attempt -> assertThat(attempt.msgSeq()).isEqualTo(1));
            assertThat(seq.attempt(outbound.get(1).localSeq())).get()
                    .satisfies(attempt -> assertThat(attempt.msgSeq()).isEqualTo(2));
            assertThat(seq.attempt(outbound.get(2).localSeq())).get()
                    .satisfies(attempt -> assertThat(attempt.msgSeq()).isEqualTo(3));
        }
    }

    @Test
    void passiveReplyRejectsAnotherSourceOutboundTargetAndBothReferenceKinds() throws Exception {
        try (SqliteMessageBoardStore board = board("reply-source.db");
             OfficialQqReplySequenceStore seq = seq("reply-source-seq.db")) {
            appendInbound(board, GROUP_A, "ROBOT1.0_INBOUND");
            appendOutboundForTest(board, GROUP_A, "outbound-id");
            FakeTransport transport = new FakeTransport();
            OfficialQqSendService service = service(board, seq, transport,
                    () -> TextSendPolicy.defaults());

            assertThatThrownBy(() -> service.reply("acc-a", PRIVATE_A, "x", "ROBOT1.0_INBOUND"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("REPLY_TARGET_NOT_FOUND"));
            assertThatThrownBy(() -> service.reply("acc-a", GROUP_A, "x", "outbound-id"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("REPLY_TARGET_NOT_FOUND"));
            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "ROBOT1.0_INBOUND", "event-1"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("REPLY_REFERENCE_CONFLICT"));
            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "", "event-1"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_ID_REPLY_UNSUPPORTED"));
            assertThat(transport.calls).isEmpty();
        }
    }

    @Test
    void eventIdWithStoreRejectsUnknownWrongAccountAndWrongSourceBeforeBoard() throws Exception {
        try (SqliteMessageBoardStore board = board("event-invalid.db");
             OfficialQqReplySequenceStore seq = seq("event-invalid-seq.db");
             OfficialQqEventReferenceStore events = events("event-invalid-events.db")) {
            recordEvent(events, "acc-a", GROUP_A, "event-known");
            events.record("acc-a", "event-message-envelope", "GROUP_MESSAGE_CREATE", GROUP_A,
                    "ROBOT1.0_EVENT_MESSAGE", Direction.INBOUND, T0);
            events.record("acc-a", "event-reject", "GROUP_MSG_REJECT", GROUP_A,
                    "", Direction.INBOUND, T0);
            FakeTransport transportA = new FakeTransport("unused-a");
            FakeTransport transportB = new FakeTransport("unused-b");
            transportB.accountId = "acc-b";
            OfficialQqSendAccounts accounts = accountId -> switch (accountId) {
                case "acc-a" -> Optional.of(new OfficialQqSendAccount("acc-a", true, transportA));
                case "acc-b" -> Optional.of(new OfficialQqSendAccount("acc-b", true, transportB));
                default -> Optional.empty();
            };
            OfficialQqSendService service = new OfficialQqSendService(
                    board, accounts, TextSendPolicy::defaults, seq, events,
                    Clock.fixed(T0, ZoneOffset.UTC));

            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "", "event-unknown"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_UNKNOWN"));

            assertThatThrownBy(() -> service.reply(
                    "acc-b", SourceAddress.parse("qq_official_group/acc-b/group-b"),
                    "x", "", "event-known"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_ACCOUNT_MISMATCH"));

            assertThatThrownBy(() -> service.reply(
                    "acc-a", PRIVATE_A, "x", "", "event-known"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_SOURCE_MISMATCH"));

            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "", "event-message-envelope"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_NOT_REPLYABLE"));
            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "", "event-reject"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_NOT_REPLYABLE"));

            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "ROBOT1.0_MSG", "event-known"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("REPLY_REFERENCE_CONFLICT"));

            assertThat(transportA.calls).isEmpty();
            assertThat(transportB.calls).isEmpty();
            assertThat(board.read(GROUP_A, 0, 20)).isEmpty();
            assertThat(board.read(PRIVATE_A, 0, 20)).isEmpty();
        }
    }

    @Test
    void eventIdWithoutInjectedStoreRemainsFailClosedBeforeBoard() throws Exception {
        try (SqliteMessageBoardStore board = board("event-missing-store.db");
             OfficialQqReplySequenceStore seq = seq("event-missing-store-seq.db")) {
            FakeTransport transport = new FakeTransport("unused");
            OfficialQqSendService service = service(board, seq, transport,
                    () -> TextSendPolicy.defaults());

            assertThatThrownBy(() -> service.reply(
                    "acc-a", GROUP_A, "x", "", "event-1"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_ID_REPLY_UNSUPPORTED"));
            assertThat(transport.calls).isEmpty();
            assertThat(board.read(GROUP_A, 0, 20)).isEmpty();
        }
    }

    @Test
    void validEventIdReplySurvivesStoreRestartAndDoesNotAllocateMsgSeq() throws Exception {
        Path boardDb = temp.resolve("event-valid.db");
        Path seqDb = temp.resolve("event-valid-seq.db");
        Path eventDb = temp.resolve("event-valid-events.db");
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(boardDb);
             OfficialQqReplySequenceStore seq = new OfficialQqReplySequenceStore(
                     seqDb, Clock.fixed(T0, ZoneOffset.UTC));
             OfficialQqEventReferenceStore events = new OfficialQqEventReferenceStore(
                     eventDb, Clock.fixed(T0, ZoneOffset.UTC))) {
            recordEvent(events, "acc-a", GROUP_A, "event-known");
            assertThat(events.requireReplyable("acc-a", GROUP_A, "event-known").eventType())
                    .isEqualTo("GROUP_MSG_RECEIVE");
            FakeTransport transport = new FakeTransport("event-sent-1");
            OfficialQqSendService service = new OfficialQqSendService(
                    board, accountsFor(transport), TextSendPolicy::defaults, seq, events,
                    Clock.fixed(T0, ZoneOffset.UTC));

            service.reply("acc-a", GROUP_A, "event reply", "", "event-known");

            assertThat(transport.calls).singleElement().satisfies(call -> {
                assertThat(call.message().eventId()).isEqualTo("event-known");
                assertThat(call.message().msgId()).isEmpty();
                assertThat(call.message().msgSeq()).isNull();
            });
        }

        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(boardDb);
             OfficialQqReplySequenceStore seq = new OfficialQqReplySequenceStore(
                     seqDb, Clock.fixed(T0, ZoneOffset.UTC));
             OfficialQqEventReferenceStore events = new OfficialQqEventReferenceStore(
                     eventDb, Clock.fixed(T0, ZoneOffset.UTC))) {
            FakeTransport transport = new FakeTransport("event-sent-2");
            OfficialQqSendService service = new OfficialQqSendService(
                    board, accountsFor(transport), TextSendPolicy::defaults, seq, events,
                    Clock.fixed(T0, ZoneOffset.UTC));

            List<OfficialQqSendOutcome> outcomes =
                    service.reply("acc-a", GROUP_A, "after restart", "", "event-known");

            assertThat(outcomes).singleElement()
                    .satisfies(outcome -> assertThat(outcome.status())
                            .isEqualTo(DeliveryStatus.SENT));
            assertThat(transport.calls).singleElement().satisfies(call ->
                    assertThat(call.message().eventId()).isEqualTo("event-known"));
            assertThat(board.read(GROUP_A, 0, 20)).hasSize(2)
                    .allSatisfy(message -> assertThat(message.delivery().status())
                            .isEqualTo(DeliveryStatus.SENT));
        }
    }

    private SqliteMessageBoardStore board(String name) {
        return new SqliteMessageBoardStore(temp.resolve(name));
    }

    private OfficialQqReplySequenceStore seq(String name) {
        return new OfficialQqReplySequenceStore(temp.resolve(name),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private OfficialQqEventReferenceStore events(String name) {
        return new OfficialQqEventReferenceStore(temp.resolve(name),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static void recordEvent(OfficialQqEventReferenceStore events, String accountId,
                                    SourceAddress source, String eventId) {
        String eventType = QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())
                ? "GROUP_MSG_RECEIVE" : "FRIEND_ADD";
        events.record(accountId, eventId, eventType, source, "",
                Direction.INBOUND, T0);
    }

    private static OfficialQqSendAccounts accountsFor(FakeTransport transport) {
        return accountId -> "acc-a".equals(accountId)
                ? Optional.of(new OfficialQqSendAccount("acc-a", true, transport))
                : Optional.empty();
    }

    private OfficialQqSendService service(MessageBoardStore board,
                                          OfficialQqReplySequenceStore seq,
                                          FakeTransport transport,
                                          Supplier<TextSendPolicy> policy) {
        OfficialQqSendAccounts accounts = accountId ->
                "acc-a".equals(accountId)
                        ? Optional.of(new OfficialQqSendAccount("acc-a", true, transport))
                        : Optional.empty();
        return new OfficialQqSendService(board, accounts, policy, seq,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static void appendInbound(SqliteMessageBoardStore board, SourceAddress source,
                                      String messageId) {
        board.append(new InboundMessage(source, messageId, T0,
                List.of(new Segment("text", "inbound")), Direction.INBOUND, "sender", "Sender"));
    }

    private static void appendOutboundForTest(SqliteMessageBoardStore board,
                                              SourceAddress source, String messageId) {
        long localSeq = board.beginOutbound(new InboundMessage(source, "out-test", T0,
                        List.of(new Segment("text", "outbound")), Direction.OUTBOUND, "acc-a", ""),
                "op-test", 1, 1).message().localSeq();
        board.finishOutbound(localSeq, DeliveryStatus.SENT, messageId, null);
    }

    private static Message onlyRow(MessageBoardStore board, SourceAddress source) {
        return board.read(source, 0, 10).getFirst();
    }

    private record Call(String target, OfficialQqSendMessage message) {
    }

    private static final class FakeTransport implements OfficialQqSendTransport {

        private final Deque<Object> results = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private String accountId = "acc-a";
        private Consumer<Call> onCall = call -> { };

        @Override
        public String accountId() {
            return accountId;
        }

        private FakeTransport(Object... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public String sendGroup(String groupOpenid, OfficialQqSendMessage message) throws Exception {
            return respond(new Call(groupOpenid, message));
        }

        @Override
        public String sendUser(String userOpenid, OfficialQqSendMessage message) throws Exception {
            return respond(new Call(userOpenid, message));
        }

        private String respond(Call call) throws Exception {
            calls.add(call);
            onCall.accept(call);
            Object next = results.pollFirst();
            if (next instanceof Exception failure) {
                throw failure;
            }
            if (next == null) {
                throw new IllegalStateException("fake transport response missing");
            }
            return (String) next;
        }
    }

    /** Board wrapper that proves finish failures never turn a provider SENT into durable success. */
    private static final class FailFinishBoard implements MessageBoardStore {

        private final MessageBoardStore delegate;

        private FailFinishBoard(MessageBoardStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendResult append(InboundMessage inbound) {
            return delegate.append(inbound);
        }

        @Override
        public AppendResult beginOutbound(InboundMessage message, String operationId,
                                          int partIndex, int partTotal) {
            return delegate.beginOutbound(message, operationId, partIndex, partTotal);
        }

        @Override
        public boolean finishOutbound(long localSeq, DeliveryStatus status,
                                      String externalMessageId, String errorCode) {
            return false;
        }

        @Override
        public int recoverPendingOutbound() {
            return delegate.recoverPendingOutbound();
        }

        @Override
        public List<Message> read(SourceAddress source, long afterSeq, int limit) {
            return delegate.read(source, afterSeq, limit);
        }

        @Override
        public List<ThreadSummary> threads(int limit) {
            return delegate.threads(limit);
        }

        @Override
        public void attachExternalMessageId(long localSeq, String externalMessageId) {
            delegate.attachExternalMessageId(localSeq, externalMessageId);
        }

        @Override
        public Optional<Message> findByExternalMessageId(SourceAddress source,
                                                          String externalMessageId) {
            return delegate.findByExternalMessageId(source, externalMessageId);
        }

        @Override
        public List<Message> search(String query, String sourcePrefix, Instant from, Instant to,
                                    long beforeSeq, int limit) {
            return delegate.search(query, sourcePrefix, from, to, beforeSeq, limit);
        }

        @Override
        public boolean ping() {
            return delegate.ping();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}

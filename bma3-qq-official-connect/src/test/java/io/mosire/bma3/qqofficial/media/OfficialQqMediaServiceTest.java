package io.mosire.bma3.qqofficial.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.mosire.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.mosire.bma3.qqofficial.send.OfficialQqTransportException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqMediaServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final SourceAddress GROUP_A =
            SourceAddress.parse("qq_official_group/acc-a/group-openid-A");
    private static final SourceAddress PRIVATE_A =
            SourceAddress.parse("qq_official_private/acc-a/user-openid-A");

    @TempDir
    Path temp;

    @Test
    void boardIsPendingBeforeUploadThenSentAndResultNeverLeaksMediaPayload() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("image.png", "image-bytes");
            fixture.transport.boardSource = GROUP_A;

            OfficialQqMediaOutcome outcome =
                    fixture.service.sendImage("acc-a", GROUP_A, file.toString(), "", "");

            assertThat(fixture.transport.calls)
                    .containsExactly("upload-group", "send-group");
            assertThat(fixture.transport.rowsAtUpload).hasSize(1);
            assertThat(fixture.transport.rowsAtUpload.getFirst()).singleElement()
                    .satisfies(row -> {
                        assertThat(row.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
                        assertThat(row.delivery().operationId()).isNotBlank();
                    });
            UploadCall upload = fixture.transport.uploads.getFirst();
            assertThat(upload.target()).isEqualTo("group-openid-A");
            assertThat(upload.fileType()).isEqualTo(1);
            assertThat(upload.fileName()).isEqualTo("image.png");
            assertThat(upload.base64())
                    .isEqualTo(Base64.getEncoder().encodeToString(
                            "image-bytes".getBytes(StandardCharsets.UTF_8)));
            assertThat(fixture.transport.sends.getFirst().message().msgId()).isEmpty();
            assertThat(fixture.transport.sends.getFirst().message().eventId()).isEmpty();

            assertThat(outcome.status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(outcome.externalMessageId()).isEqualTo("ROBOT1.0_MEDIA");
            assertThat(outcome.uploadState()).isEqualTo(OfficialQqMediaUploadState.UPLOADED);
            assertThat(outcome.json().toString())
                    .doesNotContain(upload.base64())
                    .doesNotContain("opaque-file-info")
                    .doesNotContain(file.toString())
                    .doesNotContain("image.png");
            assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.SENT);
            assertThat(fixture.onlyOutbound(GROUP_A).externalMessageId())
                    .isEqualTo("ROBOT1.0_MEDIA");
        }
    }

    @Test
    void uploadUnknownBecomesUploadUnknownAndNeverDispatchesMessage() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("file.bin", "payload");
            fixture.transport.boardSource = GROUP_A;
            fixture.transport.uploadFailure =
                    OfficialQqMediaTransportException.unknown("UPLOAD_TIMEOUT");

            OfficialQqMediaOutcome outcome =
                    fixture.service.sendFile("acc-a", GROUP_A, file.toString(), "", "");

            assertThat(fixture.transport.calls).containsExactly("upload-group");
            assertThat(fixture.transport.sendCalls).isZero();
            assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(outcome.errorCode()).isEqualTo(OfficialQqMediaService.UPLOAD_UNKNOWN);
            assertThat(outcome.uploadState())
                    .isEqualTo(OfficialQqMediaUploadState.UPLOAD_UNKNOWN);
            assertThat(outcome.dispatched()).isFalse();
            assertThat(outcome.resendAllowed()).isFalse();
            assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.UNKNOWN);
        }
    }

    @Test
    void rejectedUploadEndsFailedBeforeSendWithoutMessageDispatch() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("file.bin", "payload");
            fixture.transport.boardSource = GROUP_A;
            fixture.transport.uploadFailure =
                    OfficialQqMediaTransportException.rejected("UPLOAD_REJECTED");

            OfficialQqMediaOutcome outcome =
                    fixture.service.sendFile("acc-a", GROUP_A, file.toString(), "", "");

            assertThat(fixture.transport.calls).containsExactly("upload-group");
            assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(outcome.errorCode()).isEqualTo("UPLOAD_REJECTED");
            assertThat(outcome.uploadState())
                    .isEqualTo(OfficialQqMediaUploadState.UPLOAD_FAILED);
            assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
        }
    }

    @Test
    void unknownMessageSendIsNeverRetriedAndStaysUnknown() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("file.bin", "payload");
            fixture.transport.boardSource = GROUP_A;
            fixture.transport.sendFailure = OfficialQqTransportException.unknown("TIMEOUT");

            OfficialQqMediaOutcome outcome =
                    fixture.service.sendFile("acc-a", GROUP_A, file.toString(), "", "");

            assertThat(fixture.transport.calls)
                    .containsExactly("upload-group", "send-group");
            assertThat(fixture.transport.uploadCalls).isEqualTo(1);
            assertThat(fixture.transport.sendCalls).isEqualTo(1);
            assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(outcome.errorCode()).isEqualTo("TIMEOUT");
            assertThat(outcome.resendAllowed()).isFalse();
            assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.UNKNOWN);
        }
    }

    @Test
    void groupAndPrivateRoutesStayOnTheirOwnUploadAndSendMethods() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path groupFile = fixture.file("group.png", "group");
            Path privateFile = fixture.file("private.txt", "private");

            fixture.transport.boardSource = GROUP_A;
            fixture.service.sendImage("acc-a", GROUP_A, groupFile.toString(), "", "");
            fixture.transport.boardSource = PRIVATE_A;
            fixture.service.sendFile("acc-a", PRIVATE_A, privateFile.toString(), "", "");

            assertThat(fixture.transport.calls).containsExactly(
                    "upload-group", "send-group", "upload-user", "send-user");
            assertThat(fixture.transport.uploads).extracting(UploadCall::target)
                    .containsExactly("group-openid-A", "user-openid-A");
            assertThat(fixture.transport.sends).extracting(SendCall::target)
                    .containsExactly("group-openid-A", "user-openid-A");
        }
    }

    @Test
    void mismatchedUploadReceiptNeverSendsAcrossAccountSceneOrTarget() throws Exception {
        List<OfficialQqMediaUpload> wrongReceipts = List.of(
                new OfficialQqMediaUpload("acc-b", OfficialQqMediaUpload.Scene.GROUP,
                        "group-openid-A", "opaque-cross-account", 300),
                new OfficialQqMediaUpload("acc-a", OfficialQqMediaUpload.Scene.PRIVATE,
                        "group-openid-A", "opaque-cross-scene", 300),
                new OfficialQqMediaUpload("acc-a", OfficialQqMediaUpload.Scene.GROUP,
                        "group-openid-B", "opaque-cross-target", 300));
        for (OfficialQqMediaUpload wrong : wrongReceipts) {
            try (Fixture fixture = new Fixture(false)) {
                Path file = fixture.file("file.bin", "payload");
                fixture.transport.boardSource = GROUP_A;
                fixture.transport.uploadResult = wrong;

                OfficialQqMediaOutcome outcome = fixture.service.sendFile(
                        "acc-a", GROUP_A, file.toString(), "", "");

                assertThat(fixture.transport.uploadCalls).isEqualTo(1);
                assertThat(fixture.transport.sendCalls).isZero();
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(outcome.errorCode())
                        .isEqualTo(OfficialQqMediaService.UPLOAD_RESPONSE_INVALID);
                assertThat(outcome.json().toString()).doesNotContain(wrong.fileInfo());
                assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                        .isEqualTo(DeliveryStatus.UNKNOWN);
            }
        }
    }

    @Test
    void expiredTtlStopsBeforeMessageSend() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("file.bin", "payload");
            fixture.transport.boardSource = GROUP_A;
            fixture.transport.uploadResult = new OfficialQqMediaUpload("acc-a",
                    OfficialQqMediaUpload.Scene.GROUP, "group-openid-A", "opaque-file-info", 1);
            fixture.transport.afterUpload =
                    () -> fixture.clock.advanceOnNextInstant(Duration.ofSeconds(2));

            OfficialQqMediaOutcome outcome =
                    fixture.service.sendFile("acc-a", GROUP_A, file.toString(), "", "");

            assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(outcome.errorCode()).isEqualTo(OfficialQqMediaService.MEDIA_TTL_EXPIRED);
            assertThat(fixture.transport.sendCalls).isZero();
            assertThat(fixture.onlyOutbound(GROUP_A).delivery().status())
                    .isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
        }
    }

    @Test
    void msgIdReplyRequiresSameSourceAndReusesDurableSequence() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("image.png", "image");
            fixture.board.append(new InboundMessage(
                    GROUP_A, "ROBOT1.0_IN", T0,
                    List.of(new Segment("text", "in")), Direction.INBOUND, "u", "U"));
            fixture.transport.boardSource = GROUP_A;

            fixture.service.sendImage("acc-a", GROUP_A, file.toString(), "ROBOT1.0_IN", "");
            fixture.service.sendImage("acc-a", GROUP_A, file.toString(), "ROBOT1.0_IN", "");

            assertThat(fixture.transport.sends).hasSize(2);
            assertThat(fixture.transport.sends.get(0).message().msgId())
                    .isEqualTo("ROBOT1.0_IN");
            assertThat(fixture.transport.sends.get(0).message().msgSeq()).isEqualTo(1);
            assertThat(fixture.transport.sends.get(1).message().msgSeq()).isEqualTo(2);

            int uploadsBefore = fixture.transport.uploadCalls;
            assertThatThrownBy(() -> fixture.service.sendImage(
                    "acc-a", PRIVATE_A, file.toString(), "ROBOT1.0_IN", ""))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code()).isEqualTo("REPLY_TARGET_NOT_FOUND"));
            assertThat(fixture.transport.uploadCalls).isEqualTo(uploadsBefore);
        }
    }

    @Test
    void eventIdReplyUsesTheExistingWhitelistAndRejectsNonWhitelistedEvents() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Path file = fixture.file("file.bin", "payload");
            fixture.events.record("acc-a", "event-ok", "GROUP_ADD_ROBOT",
                    GROUP_A, "", Direction.INBOUND, T0);
            fixture.events.record("acc-a", "event-bad", "GROUP_DEL_ROBOT",
                    GROUP_A, "", Direction.INBOUND, T0);
            fixture.transport.boardSource = GROUP_A;

            fixture.service.sendFile("acc-a", GROUP_A, file.toString(), "", "event-ok");
            assertThat(fixture.transport.sends.getFirst().message().eventId())
                    .isEqualTo("event-ok");
            assertThat(fixture.transport.sends.getFirst().message().msgSeq()).isNull();

            int uploadsBefore = fixture.transport.uploadCalls;
            assertThatThrownBy(() -> fixture.service.sendFile(
                    "acc-a", GROUP_A, file.toString(), "", "event-bad"))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("EVENT_REFERENCE_NOT_REPLYABLE"));
            assertThat(fixture.transport.uploadCalls).isEqualTo(uploadsBefore);
        }
    }

    @Test
    void accountTransportBindingRejectsCrossAccountBeforeAnySend() {
        OfficialQqMediaTransportPort wrongAccount = new OfficialQqMediaTransportPort() {
            @Override
            public String accountId() {
                return "acc-b";
            }

            @Override
            public OfficialQqMediaUpload uploadGroup(String groupOpenid, int fileType,
                                                     String fileName, String base64Data) {
                return new OfficialQqMediaUpload("acc-b", OfficialQqMediaUpload.Scene.GROUP,
                        groupOpenid, "opaque", 300);
            }

            @Override
            public OfficialQqMediaUpload uploadUser(String userOpenid, int fileType,
                                                    String fileName, String base64Data) {
                return new OfficialQqMediaUpload("acc-b", OfficialQqMediaUpload.Scene.PRIVATE,
                        userOpenid, "opaque", 300);
            }

            @Override
            public String sendGroupMedia(String groupOpenid, OfficialQqMediaMessage message) {
                return "must-not-send";
            }

            @Override
            public String sendUserMedia(String userOpenid, OfficialQqMediaMessage message) {
                return "must-not-send";
            }
        };

        assertThatThrownBy(() -> new OfficialQqMediaAccount("acc-a", true, wrongAccount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountSourceMismatchAndDisabledAccountFailBeforeBoardOrUpload() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Path file = fixture.file("file.bin", "payload");

            assertThatThrownBy(() -> fixture.service.sendFile(
                    "acc-b", GROUP_A, file.toString(), "", ""))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("SOURCE_ACCOUNT_MISMATCH"));
            assertThat(fixture.transport.uploadCalls).isZero();
            assertThat(fixture.board.read(GROUP_A, 0, 10)).isEmpty();

            OfficialQqMediaAccount disabled =
                    new OfficialQqMediaAccount("acc-a", false, fixture.transport);
            OfficialQqMediaService disabledService = new OfficialQqMediaService(
                    fixture.board,
                    accountId -> Optional.of(disabled),
                    fixture.localMedia,
                    fixture.sequences,
                    null,
                    fixture.clock);
            assertThatThrownBy(() -> disabledService.sendFile(
                    "acc-a", GROUP_A, file.toString(), "", ""))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code()).isEqualTo("ACCOUNT_DISABLED"));
            assertThat(fixture.transport.uploadCalls).isZero();
        }
    }

    private final class Fixture implements AutoCloseable {

        private final SqliteMessageBoardStore board;
        private final OfficialQqReplySequenceStore sequences;
        private final OfficialQqEventReferenceStore events;
        private final OfficialQqLocalMediaPolicy localMedia;
        private final MutableClock clock = new MutableClock(T0);
        private final FakeTransport transport;
        private final OfficialQqMediaService service;
        private final Path dataDir;

        private Fixture(boolean withEventStore) throws Exception {
            String suffix = UUID.randomUUID().toString();
            board = new SqliteMessageBoardStore(temp.resolve("board-" + suffix + ".db"));
            sequences = new OfficialQqReplySequenceStore(
                    temp.resolve("seq-" + suffix + ".db"), clock);
            events = withEventStore
                    ? new OfficialQqEventReferenceStore(temp.resolve("events-" + suffix + ".db"), clock)
                    : null;
            dataDir = Files.createDirectory(temp.resolve("data-" + suffix));
            localMedia = new OfficialQqLocalMediaPolicy(dataDir);
            transport = new FakeTransport(board);
            OfficialQqMediaAccounts accounts = accountId ->
                    "acc-a".equals(accountId)
                            ? Optional.of(new OfficialQqMediaAccount("acc-a", true, transport))
                            : Optional.empty();
            service = new OfficialQqMediaService(
                    board, accounts, localMedia, sequences, events, clock);
        }

        private Path file(String name, String content) throws Exception {
            Path path = dataDir.resolve(name);
            Files.writeString(path, content);
            return path;
        }

        private Message onlyOutbound(SourceAddress source) {
            return board.read(source, 0, 10).stream()
                    .filter(message -> message.inbound().direction() == Direction.OUTBOUND)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            if (events != null) {
                events.close();
            }
            sequences.close();
            board.close();
        }
    }

    private static final class FakeTransport implements OfficialQqMediaTransportPort {

        private final SqliteMessageBoardStore board;
        private final List<String> calls = new ArrayList<>();
        private final List<List<Message>> rowsAtUpload = new ArrayList<>();
        private final List<UploadCall> uploads = new ArrayList<>();
        private final List<SendCall> sends = new ArrayList<>();
        private SourceAddress boardSource = GROUP_A;
        private OfficialQqMediaUpload uploadResult;
        private OfficialQqMediaTransportException uploadFailure;
        private OfficialQqTransportException sendFailure;
        private String sendResult = "ROBOT1.0_MEDIA";
        private Runnable afterUpload = () -> { };
        private int uploadCalls;
        private int sendCalls;

        private FakeTransport(SqliteMessageBoardStore board) {
            this.board = board;
        }

        @Override
        public String accountId() {
            return "acc-a";
        }

        @Override
        public OfficialQqMediaUpload uploadGroup(String groupOpenid, int fileType,
                                                 String fileName, String base64Data)
                throws OfficialQqMediaTransportException {
            return upload("group", groupOpenid, fileType, fileName, base64Data);
        }

        @Override
        public OfficialQqMediaUpload uploadUser(String userOpenid, int fileType,
                                                String fileName, String base64Data)
                throws OfficialQqMediaTransportException {
            return upload("user", userOpenid, fileType, fileName, base64Data);
        }

        private OfficialQqMediaUpload upload(String scene, String target, int fileType,
                                             String fileName, String base64Data)
                throws OfficialQqMediaTransportException {
            calls.add("upload-" + scene);
            uploadCalls++;
            rowsAtUpload.add(List.copyOf(board.read(boardSource, 0, 10)));
            uploads.add(new UploadCall(scene, target, fileType, fileName, base64Data));
            afterUpload.run();
            if (uploadFailure != null) {
                throw uploadFailure;
            }
            return uploadResult != null ? uploadResult : new OfficialQqMediaUpload(accountId(),
                    "group".equals(scene) ? OfficialQqMediaUpload.Scene.GROUP
                            : OfficialQqMediaUpload.Scene.PRIVATE,
                    target, "opaque-file-info", 300);
        }

        @Override
        public String sendGroupMedia(String groupOpenid, OfficialQqMediaMessage message)
                throws OfficialQqTransportException {
            return send("group", groupOpenid, message);
        }

        @Override
        public String sendUserMedia(String userOpenid, OfficialQqMediaMessage message)
                throws OfficialQqTransportException {
            return send("user", userOpenid, message);
        }

        private String send(String scene, String target, OfficialQqMediaMessage message)
                throws OfficialQqTransportException {
            calls.add("send-" + scene);
            sendCalls++;
            sends.add(new SendCall(scene, target, message));
            if (sendFailure != null) {
                throw sendFailure;
            }
            return sendResult;
        }
    }

    private record UploadCall(String scene, String target, int fileType,
                              String fileName, String base64) {
    }

    private record SendCall(String scene, String target, OfficialQqMediaMessage message) {
    }

    private static final class MutableClock extends Clock {

        private Instant now;
        private Duration advanceAfterNextRead;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceOnNextInstant(Duration duration) {
            advanceAfterNextRead = duration;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = now;
            if (advanceAfterNextRead != null) {
                now = now.plus(advanceAfterNextRead);
                advanceAfterNextRead = null;
            }
            return value;
        }
    }
}

package io.github.c1589190.bma3.qqofficial.media;

import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceException;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendException;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqTransportException;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Safe local-only official QQ media send path.
 *
 * <p>Ordering is non-negotiable: local account/source/reply/file validation, then a durable
 * outbound board row in {@code PENDING}, then the fixed files upload with
 * {@code srv_send_msg=false}, then one fixed {@code msg_type=7} message send. An uncertain upload
 * becomes {@code UPLOAD_UNKNOWN} and never proceeds to the message send. An uncertain message send
 * becomes {@code UNKNOWN} and is never retried.</p>
 *
 * <p>This phase intentionally implements only direct upload of local files up to 5 MiB. Chunked
 * upload and message deletion are outside this class.</p>
 */
public final class OfficialQqMediaService {

    public static final String BOARD_WRITE_FAILED = "BOARD_WRITE_FAILED";
    public static final String SEQ_ALLOCATION_FAILED = "SEQ_ALLOCATION_FAILED";
    public static final String UPLOAD_UNKNOWN = "UPLOAD_UNKNOWN";
    public static final String UPLOAD_RESPONSE_INVALID = "UPLOAD_RESPONSE_INVALID";
    public static final String MEDIA_TTL_EXPIRED = "MEDIA_TTL_EXPIRED";
    public static final String MEDIA_ENCODE_FAILED = "MEDIA_ENCODE_FAILED";
    public static final String MALFORMED_RESPONSE = "MALFORMED_RESPONSE";
    public static final String PROVIDER_EXCEPTION = "PROVIDER_EXCEPTION";

    private final MessageBoardStore board;
    private final OfficialQqMediaAccounts accounts;
    private final OfficialQqLocalMediaPolicy localMedia;
    private final OfficialQqReplySequenceStore sequences;
    private final OfficialQqEventReferenceStore eventReferences;
    private final Clock clock;

    public OfficialQqMediaService(
            MessageBoardStore board,
            OfficialQqMediaAccounts accounts,
            OfficialQqLocalMediaPolicy localMedia,
            OfficialQqReplySequenceStore sequences) {
        this(board, accounts, localMedia, sequences, null, Clock.systemUTC());
    }

    public OfficialQqMediaService(
            MessageBoardStore board,
            OfficialQqMediaAccounts accounts,
            OfficialQqLocalMediaPolicy localMedia,
            OfficialQqReplySequenceStore sequences,
            OfficialQqEventReferenceStore eventReferences) {
        this(board, accounts, localMedia, sequences, eventReferences, Clock.systemUTC());
    }

    public OfficialQqMediaService(
            MessageBoardStore board,
            OfficialQqMediaAccounts accounts,
            OfficialQqLocalMediaPolicy localMedia,
            OfficialQqReplySequenceStore sequences,
            OfficialQqEventReferenceStore eventReferences,
            Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.localMedia = Objects.requireNonNull(localMedia, "localMedia");
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.eventReferences = eventReferences;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OfficialQqMediaOutcome sendImage(String accountId, SourceAddress source, String path,
                                            String replyToMessageId, String eventId)
            throws OfficialQqMediaException {
        return sendMedia(accountId, source, path, 1, replyToMessageId, eventId);
    }

    public OfficialQqMediaOutcome sendFile(String accountId, SourceAddress source, String path,
                                           String replyToMessageId, String eventId)
            throws OfficialQqMediaException {
        return sendMedia(accountId, source, path, 4, replyToMessageId, eventId);
    }

    public OfficialQqMediaOutcome sendMedia(String accountId, SourceAddress source, String path,
                                            int fileType, String replyToMessageId, String eventId)
            throws OfficialQqMediaException {
        OfficialQqMediaAccount account = requireAccount(accountId, source);
        String msgId = replyToMessageId == null ? "" : replyToMessageId.strip();
        String event = eventId == null ? "" : eventId.strip();
        validateReplyReference(account.accountId(), source, msgId, event);

        // Local path/size/type validation must complete before the board row is written.
        OfficialQqLocalMedia media = localMedia.resolve(path, fileType);
        String operationId = "op-" + UUID.randomUUID();
        InboundMessage outbound = new InboundMessage(
                source,
                "out-media-" + UUID.randomUUID(),
                clock.instant(),
                List.of(new Segment(
                        media.fileType() == 1 ? "image" : "file",
                        media.fileName(),
                        Map.of("name", media.fileName(), "bytes", Long.toString(media.bytes())))),
                Direction.OUTBOUND,
                "",
                "");
        AppendResult begun;
        try {
            begun = board.beginOutbound(outbound, operationId, 1, 1);
        } catch (RuntimeException failure) {
            return boardWriteFailure(operationId, media);
        }

        Integer msgSeq = null;
        if (!msgId.isEmpty()) {
            try {
                msgSeq = sequences.allocate(
                        begun.message().localSeq(), operationId, 1, source.format(), msgId, event)
                        .msgSeq();
            } catch (OfficialQqSendException conflict) {
                return finishBeforeSend(
                        begun, operationId, media, conflict.code(),
                        OfficialQqMediaUploadState.NOT_STARTED);
            } catch (RuntimeException failure) {
                return finishBeforeSend(
                        begun, operationId, media, SEQ_ALLOCATION_FAILED,
                        OfficialQqMediaUploadState.NOT_STARTED);
            }
        }

        String base64;
        try {
            base64 = media.base64Data();
        } catch (RuntimeException failure) {
            return finishBeforeSend(
                    begun, operationId, media, MEDIA_ENCODE_FAILED,
                    OfficialQqMediaUploadState.NOT_STARTED);
        }

        UploadAttempt upload = upload(account.transport(), source, media, base64);
        if (upload.state() != OfficialQqMediaUploadState.UPLOADED) {
            ProviderResult failure = new ProviderResult(
                    upload.state() == OfficialQqMediaUploadState.UPLOAD_UNKNOWN
                            ? DeliveryStatus.UNKNOWN : DeliveryStatus.FAILED_BEFORE_SEND,
                    "",
                    upload.errorCode(),
                    false);
            return finish(begun, operationId, media, failure, upload.state());
        }

        Instant uploadReceivedAt = clock.instant();
        BoundUpload bound;
        try {
            bound = bindUpload(account, source, upload.upload(), uploadReceivedAt);
        } catch (IllegalArgumentException invalid) {
            ProviderResult failure = new ProviderResult(
                    DeliveryStatus.UNKNOWN, "", UPLOAD_RESPONSE_INVALID, false);
            return finish(begun, operationId, media, failure,
                    OfficialQqMediaUploadState.UPLOAD_UNKNOWN);
        }
        if (expired(bound)) {
            ProviderResult failure = new ProviderResult(
                    DeliveryStatus.FAILED_BEFORE_SEND, "", MEDIA_TTL_EXPIRED, false);
            return finish(begun, operationId, media, failure,
                    OfficialQqMediaUploadState.UPLOADED);
        }

        OfficialQqMediaMessage message;
        try {
            message = new OfficialQqMediaMessage(
                    bound.upload().fileInfo(), msgId, event, msgSeq);
        } catch (IllegalArgumentException invalid) {
            ProviderResult failure = new ProviderResult(
                    DeliveryStatus.FAILED_BEFORE_SEND, "", "INVALID_REQUEST", false);
            return finish(begun, operationId, media, failure,
                    OfficialQqMediaUploadState.UPLOADED);
        }

        ProviderResult sent = dispatch(account.transport(), source, message);
        return finish(begun, operationId, media, sent, OfficialQqMediaUploadState.UPLOADED);
    }

    private UploadAttempt upload(OfficialQqMediaTransportPort transport, SourceAddress source,
                                 OfficialQqLocalMedia media, String base64) {
        try {
            OfficialQqMediaUpload uploaded = switch (source.platform()) {
                case QqOfficialSourceKeyer.GROUP_PLATFORM -> transport.uploadGroup(
                        source.lastSegment(), media.fileType(), media.fileName(), base64);
                case QqOfficialSourceKeyer.PRIVATE_PLATFORM -> transport.uploadUser(
                        source.lastSegment(), media.fileType(), media.fileName(), base64);
                default -> throw new IllegalArgumentException("不是官方消息来源");
            };
            if (uploaded == null) {
                return new UploadAttempt(null, OfficialQqMediaUploadState.UPLOAD_UNKNOWN,
                        UPLOAD_RESPONSE_INVALID);
            }
            return new UploadAttempt(uploaded, OfficialQqMediaUploadState.UPLOADED, null);
        } catch (OfficialQqMediaTransportException failure) {
            return switch (failure.kind()) {
                case NOT_DISPATCHED, REJECTED -> new UploadAttempt(
                        null, OfficialQqMediaUploadState.UPLOAD_FAILED, failure.errorCode());
                case UNKNOWN -> new UploadAttempt(
                        null, OfficialQqMediaUploadState.UPLOAD_UNKNOWN, UPLOAD_UNKNOWN);
            };
        } catch (RuntimeException failure) {
            return new UploadAttempt(
                    null, OfficialQqMediaUploadState.UPLOAD_UNKNOWN, UPLOAD_UNKNOWN);
        }
    }

    private ProviderResult dispatch(OfficialQqMediaTransportPort transport, SourceAddress source,
                                    OfficialQqMediaMessage message) {
        try {
            String externalId = switch (source.platform()) {
                case QqOfficialSourceKeyer.GROUP_PLATFORM ->
                        transport.sendGroupMedia(source.lastSegment(), message);
                case QqOfficialSourceKeyer.PRIVATE_PLATFORM ->
                        transport.sendUserMedia(source.lastSegment(), message);
                default -> throw new IllegalArgumentException("不是官方消息来源");
            };
            String safeId = safeExternalId(externalId);
            if (safeId.isEmpty()) {
                return new ProviderResult(
                        DeliveryStatus.UNKNOWN, "", MALFORMED_RESPONSE, true);
            }
            return new ProviderResult(DeliveryStatus.SENT, safeId, null, true);
        } catch (OfficialQqTransportException typed) {
            if (typed.dispatched()) {
                return new ProviderResult(DeliveryStatus.UNKNOWN, "", typed.errorCode(), true);
            }
            return new ProviderResult(
                    DeliveryStatus.FAILED_BEFORE_SEND, "", typed.errorCode(), false);
        } catch (Exception failure) {
            return new ProviderResult(DeliveryStatus.UNKNOWN, "", PROVIDER_EXCEPTION, true);
        }
    }

    private BoundUpload bindUpload(OfficialQqMediaAccount account, SourceAddress source,
                                   OfficialQqMediaUpload upload, Instant receivedAt) {
        if (upload == null || !account.accountId().equals(account.transport().accountId())) {
            throw new IllegalArgumentException("upload binding mismatch");
        }
        String target = source.lastSegment();
        OfficialQqMediaUpload.Scene scene = switch (source.platform()) {
            case QqOfficialSourceKeyer.GROUP_PLATFORM -> OfficialQqMediaUpload.Scene.GROUP;
            case QqOfficialSourceKeyer.PRIVATE_PLATFORM -> OfficialQqMediaUpload.Scene.PRIVATE;
            default -> throw new IllegalArgumentException("upload binding mismatch");
        };
        if (!source.accountId().equals(account.accountId()) || target.isBlank()
                || !account.accountId().equals(upload.accountId())
                || scene != upload.scene() || !target.equals(upload.targetOpenid())) {
            throw new IllegalArgumentException("upload binding mismatch");
        }
        Instant expiresAt = Instant.MAX;
        if (upload.ttlSeconds() > 0) {
            try {
                expiresAt = receivedAt.plusSeconds(upload.ttlSeconds());
            } catch (RuntimeException overflow) {
                expiresAt = Instant.MAX;
            }
        }
        return new BoundUpload(account.accountId(), source.format(), target, upload, expiresAt);
    }

    private boolean expired(BoundUpload bound) {
        return !clock.instant().isBefore(bound.expiresAt());
    }

    private OfficialQqMediaOutcome finish(
            AppendResult begun,
            String operationId,
            OfficialQqLocalMedia media,
            ProviderResult provider,
            OfficialQqMediaUploadState uploadState) {
        long localSeq = begun.message().localSeq();
        boolean recorded;
        try {
            recorded = board.finishOutbound(localSeq, provider.status(),
                    provider.externalMessageId(), provider.errorCode());
        } catch (RuntimeException failure) {
            recorded = false;
        }
        if (recorded) {
            return new OfficialQqMediaOutcome(operationId, localSeq, provider.status(), null,
                    provider.externalMessageId(), provider.errorCode(), true,
                    provider.dispatched(), media.fileType(), media.bytes(), uploadState);
        }
        return new OfficialQqMediaOutcome(operationId, localSeq,
                DeliveryStatus.UNKNOWN, provider.status(), "", BOARD_WRITE_FAILED,
                false, provider.dispatched(), media.fileType(), media.bytes(), uploadState);
    }

    private OfficialQqMediaOutcome finishBeforeSend(
            AppendResult begun,
            String operationId,
            OfficialQqLocalMedia media,
            String errorCode,
            OfficialQqMediaUploadState uploadState) {
        return finish(begun, operationId, media,
                new ProviderResult(DeliveryStatus.FAILED_BEFORE_SEND, "", errorCode, false),
                uploadState);
    }

    private OfficialQqMediaOutcome boardWriteFailure(
            String operationId, OfficialQqLocalMedia media) {
        return new OfficialQqMediaOutcome(operationId, null,
                DeliveryStatus.FAILED_BEFORE_SEND, null, "", BOARD_WRITE_FAILED,
                false, false, media.fileType(), media.bytes(),
                OfficialQqMediaUploadState.NOT_STARTED);
    }

    private void validateReplyReference(String accountId, SourceAddress source,
                                        String msgId, String eventId)
            throws OfficialQqMediaException {
        if (!msgId.isEmpty() && !eventId.isEmpty()) {
            throw new OfficialQqMediaException(
                    "REPLY_REFERENCE_CONFLICT", "msg_id 与 event_id 只能二选一");
        }
        if (!eventId.isEmpty()) {
            requireReplyableEvent(accountId, source, eventId);
        } else if (!msgId.isEmpty()) {
            requireInboundSameSource(source, msgId);
        }
    }

    private OfficialQqMediaAccount requireAccount(String accountId, SourceAddress source)
            throws OfficialQqMediaException {
        String requested = accountId == null ? "" : accountId;
        if (requested.isBlank()) {
            throw new OfficialQqMediaException("ACCOUNT_REQUIRED", "accountId 不能为空");
        }
        if (!requested.equals(requested.strip()) || requested.length() > 128
                || !safeAccountId(requested)) {
            throw new OfficialQqMediaException("INVALID_ACCOUNT_ID", "accountId 格式无效");
        }
        if (source == null) {
            throw new OfficialQqMediaException("INVALID_SOURCE", "source 不能为空");
        }
        try {
            QqOfficialSourceKeyer.validateOfficialSource(source);
        } catch (RuntimeException invalid) {
            throw new OfficialQqMediaException(
                    "INVALID_SOURCE", "source 必须是官方群或单聊来源");
        }
        if (!QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())
                && !QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(source.platform())) {
            throw new OfficialQqMediaException(
                    "INVALID_SOURCE", "官方媒体发送只支持群聊或单聊来源");
        }
        if (!requested.equals(source.accountId())) {
            throw new OfficialQqMediaException(
                    "SOURCE_ACCOUNT_MISMATCH", "accountId 与 source 的账号不一致");
        }
        OfficialQqMediaAccount account;
        try {
            Optional<OfficialQqMediaAccount> resolved = accounts.resolve(requested);
            if (resolved == null || resolved.isEmpty()) {
                throw new OfficialQqMediaException(
                        "ACCOUNT_NOT_FOUND", "未找到已配置的官方账号");
            }
            account = resolved.get();
        } catch (OfficialQqMediaException notFound) {
            throw notFound;
        } catch (RuntimeException failure) {
            throw new OfficialQqMediaException("ACCOUNT_LOOKUP_FAILED", "读取官方账号配置失败");
        }
        if (account == null || !requested.equals(account.accountId())) {
            throw new OfficialQqMediaException("ACCOUNT_MISMATCH", "账号解析结果不匹配");
        }
        if (!account.enabled()) {
            throw new OfficialQqMediaException("ACCOUNT_DISABLED", "官方账号未启用");
        }
        if (account.transport() == null
                || !requested.equals(account.transport().accountId())) {
            throw new OfficialQqMediaException(
                    "ACCOUNT_TRANSPORT_MISMATCH", "账号与 transport 绑定不一致");
        }
        return account;
    }

    private void requireReplyableEvent(String accountId, SourceAddress source, String eventId)
            throws OfficialQqMediaException {
        if (eventReferences == null) {
            throw new OfficialQqMediaException(
                    "EVENT_ID_REPLY_UNSUPPORTED", "当前不支持仅用 event_id 的被动回复");
        }
        try {
            eventReferences.requireReplyable(accountId, source, eventId);
        } catch (OfficialQqEventReferenceException failure) {
            throw new OfficialQqMediaException(
                    eventReferenceErrorCode(failure.code()),
                    eventReferenceSafeMessage(failure.code()));
        } catch (RuntimeException failure) {
            throw new OfficialQqMediaException(
                    "EVENT_REFERENCE_LOOKUP_FAILED", "官方事件引用校验失败");
        }
    }

    private void requireInboundSameSource(SourceAddress source, String msgId)
            throws OfficialQqMediaException {
        Optional<Message> found;
        try {
            found = board.findByExternalMessageId(source, msgId);
        } catch (RuntimeException failure) {
            throw new OfficialQqMediaException("BOARD_READ_FAILED", "读取消息板失败");
        }
        if (found.isEmpty()
                || found.get().inbound().direction() != Direction.INBOUND
                || !msgId.equals(found.get().inbound().messageId())) {
            throw new OfficialQqMediaException(
                    "REPLY_TARGET_NOT_FOUND", "被动回复的 msg_id 不是该来源的真实入站消息");
        }
    }

    private static String eventReferenceErrorCode(OfficialQqEventReferenceException.Code code) {
        return switch (code) {
            case UNKNOWN_EVENT_REFERENCE -> "EVENT_REFERENCE_UNKNOWN";
            case ACCOUNT_MISMATCH -> "EVENT_REFERENCE_ACCOUNT_MISMATCH";
            case SOURCE_MISMATCH, ACCOUNT_SOURCE_MISMATCH ->
                    "EVENT_REFERENCE_SOURCE_MISMATCH";
            case NOT_INBOUND, NOT_REPLYABLE -> "EVENT_REFERENCE_NOT_REPLYABLE";
            case STORAGE_ERROR -> "EVENT_REFERENCE_LOOKUP_FAILED";
            default -> "EVENT_REFERENCE_REJECTED";
        };
    }

    private static String eventReferenceSafeMessage(
            OfficialQqEventReferenceException.Code code) {
        return switch (code) {
            case UNKNOWN_EVENT_REFERENCE -> "未观察到该官方入站 eventId";
            case ACCOUNT_MISMATCH -> "eventId 不属于该官方账号";
            case SOURCE_MISMATCH, ACCOUNT_SOURCE_MISMATCH ->
                    "eventId 不属于该 raw source";
            case NOT_INBOUND, NOT_REPLYABLE -> "该事件引用不是可回复的真实入站事件";
            case STORAGE_ERROR -> "官方事件引用存储不可用";
            default -> "官方事件引用校验未通过";
        };
    }

    private static boolean safeAccountId(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (codePoint == '/' || codePoint == '\\'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static String safeExternalId(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > 512) {
            return "";
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return "";
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private record UploadAttempt(
            OfficialQqMediaUpload upload,
            OfficialQqMediaUploadState state,
            String errorCode) {
    }

    private record BoundUpload(
            String accountId,
            String source,
            String target,
            OfficialQqMediaUpload upload,
            Instant expiresAt) {
    }

    private record ProviderResult(
            DeliveryStatus status,
            String externalMessageId,
            String errorCode,
            boolean dispatched) {

        private ProviderResult {
            Objects.requireNonNull(status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException("provider 结果必须是终止状态");
            }
        }
    }
}

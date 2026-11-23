package io.github.c1589190.bma3.qqofficial.send;

import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.message.TextSendPolicy;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceException;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Official QQ text send service with the same safety contract as the OneBot outbound path.
 *
 * <p>Every actual chunk is persisted as {@code PENDING} before dispatch. Only an explicit provider
 * success becomes {@code SENT}; timeout, network failure, non-success classification, a
 * post-dispatch exception or a failed terminal board write is {@code UNKNOWN}. A local failure
 * before dispatch is {@code FAILED_BEFORE_SEND}. No path retries a write request.</p>
 *
 * <p>Passive {@code msg_id} replies must point at an inbound message from the exact same raw
 * source. Their {@code msg_seq} is allocated atomically and durably after the board PENDING row
 * exists, before dispatch, and is never rolled back due to a provider failure.</p>
 */
public final class OfficialQqSendService {

    public static final String BOARD_WRITE_FAILED = "BOARD_WRITE_FAILED";
    public static final String SEQ_ALLOCATION_FAILED = "SEQ_ALLOCATION_FAILED";
    public static final String PROVIDER_EXCEPTION = "PROVIDER_EXCEPTION";
    public static final String TIMEOUT = "TIMEOUT";

    private final MessageBoardStore board;
    private final OfficialQqSendAccounts accounts;
    private final Supplier<TextSendPolicy> textPolicy;
    private final OfficialQqReplySequenceStore sequences;
    private final OfficialQqEventReferenceStore eventReferences;
    private final Clock clock;

    public OfficialQqSendService(
            MessageBoardStore board,
            OfficialQqSendAccounts accounts,
            Supplier<TextSendPolicy> textPolicy,
            OfficialQqReplySequenceStore sequences) {
        this(board, accounts, textPolicy, sequences, null, Clock.systemUTC());
    }

    public OfficialQqSendService(
            MessageBoardStore board,
            OfficialQqSendAccounts accounts,
            Supplier<TextSendPolicy> textPolicy,
            OfficialQqReplySequenceStore sequences,
            Clock clock) {
        this(board, accounts, textPolicy, sequences, null, clock);
    }

    /**
     * Optional event-reference capability. Without it, eventId-only replies stay fail-closed.
     * When present, every eventId is verified against the exact account/source before a board row
     * is written or a provider request is dispatched.
     */
    public OfficialQqSendService(
            MessageBoardStore board,
            OfficialQqSendAccounts accounts,
            Supplier<TextSendPolicy> textPolicy,
            OfficialQqReplySequenceStore sequences,
            OfficialQqEventReferenceStore eventReferences) {
        this(board, accounts, textPolicy, sequences, eventReferences, Clock.systemUTC());
    }

    public OfficialQqSendService(
            MessageBoardStore board,
            OfficialQqSendAccounts accounts,
            Supplier<TextSendPolicy> textPolicy,
            OfficialQqReplySequenceStore sequences,
            OfficialQqEventReferenceStore eventReferences,
            Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.textPolicy = textPolicy == null ? TextSendPolicy::defaults : textPolicy;
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.eventReferences = eventReferences;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Active text send; the account and raw source must match exactly. */
    public List<OfficialQqSendOutcome> sendText(String accountId, SourceAddress source,
                                                 String text) throws OfficialQqSendException {
        OfficialQqSendAccount account = requireAccount(accountId, source);
        return sendChunks(account, source, chunks(requireText(text)), "", "");
    }

    /** Passive reply by real inbound {@code msg_id}. */
    public List<OfficialQqSendOutcome> reply(String accountId, SourceAddress source, String text,
                                              String replyToMessageId)
            throws OfficialQqSendException {
        return reply(accountId, source, text, replyToMessageId, "");
    }

    /**
     * Passive reply by either {@code msg_id} or {@code event_id}. Supplying both is rejected;
     * supplying neither is also rejected because this is the reply tool, not active send.
     */
    public List<OfficialQqSendOutcome> reply(String accountId, SourceAddress source, String text,
                                              String replyToMessageId, String eventId)
            throws OfficialQqSendException {
        OfficialQqSendAccount account = requireAccount(accountId, source);
        String msgId = replyToMessageId == null ? "" : replyToMessageId.strip();
        String event = eventId == null ? "" : eventId.strip();
        if (!msgId.isEmpty() && !event.isEmpty()) {
            throw new OfficialQqSendException(
                    "REPLY_REFERENCE_CONFLICT", "msg_id 与 event_id 只能二选一");
        }
        if (msgId.isEmpty() && event.isEmpty()) {
            throw new OfficialQqSendException(
                    "REPLY_TARGET_REQUIRED", "被动回复必须提供 msg_id");
        }
        if (!event.isEmpty()) {
            requireReplyableEvent(account.accountId(), source, event);
            return sendChunks(account, source, chunks(requireText(text)), "", event);
        }
        requireInboundSameSource(source, msgId);
        return sendChunks(account, source, chunks(requireText(text)), msgId, "");
    }

    private List<OfficialQqSendOutcome> sendChunks(
            OfficialQqSendAccount account,
            SourceAddress source,
            List<String> chunks,
            String msgId,
            String eventId) {
        String operationId = "op-" + UUID.randomUUID();
        List<OfficialQqSendOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            int partIndex = index + 1;
            InboundMessage outbound = new InboundMessage(
                    source,
                    "out-" + UUID.randomUUID(),
                    Instant.now(clock),
                    List.of(new Segment("text", chunk)),
                    Direction.OUTBOUND,
                    account.accountId(),
                    "");
            AppendResult begun;
            try {
                begun = board.beginOutbound(outbound, operationId, partIndex, chunks.size());
            } catch (RuntimeException failure) {
                outcomes.add(boardWriteFailure(operationId, partIndex, chunks.size()));
                break;
            }

            Integer msgSeq = null;
            if (!msgId.isEmpty()) {
                try {
                    OfficialQqReplySequenceStore.ReplyAttempt attempt = sequences.allocate(
                            begun.message().localSeq(), operationId, partIndex,
                            source.format(), msgId, eventId);
                    msgSeq = attempt.msgSeq();
                } catch (OfficialQqSendException conflict) {
                    outcomes.add(finishBeforeDispatch(
                            begun, operationId, partIndex, chunks.size(), conflict.code()));
                    break;
                } catch (RuntimeException failure) {
                    outcomes.add(finishBeforeDispatch(
                            begun, operationId, partIndex, chunks.size(), SEQ_ALLOCATION_FAILED));
                    break;
                }
            }

            OfficialQqSendMessage message = new OfficialQqSendMessage(
                    chunk, msgId, eventId, msgSeq);
            ProviderResult provider = dispatch(account.transport(), source, message);
            OfficialQqSendOutcome outcome = finish(
                    begun, operationId, partIndex, chunks.size(), provider);
            outcomes.add(outcome);
            if (outcome.status() != DeliveryStatus.SENT || !outcome.recorded()) {
                break;
            }
        }
        return List.copyOf(outcomes);
    }

    private List<String> chunks(String text) throws OfficialQqSendException {
        TextSendPolicy policy;
        try {
            policy = textPolicy.get();
            if (policy == null) {
                policy = TextSendPolicy.defaults();
            }
            return policy.chunks(text);
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage();
            String code = message != null && message.startsWith("TEXT_TOO_LONG")
                    ? "TEXT_TOO_LONG" : "INVALID_ARGUMENT";
            throw new OfficialQqSendException(code, message == null ? code : message);
        }
    }

    private OfficialQqSendAccount requireAccount(String accountId, SourceAddress source)
            throws OfficialQqSendException {
        String requested = accountId == null ? "" : accountId;
        if (requested.isBlank()) {
            throw new OfficialQqSendException("ACCOUNT_REQUIRED", "accountId 不能为空");
        }
        if (!requested.equals(requested.strip()) || requested.length() > 128
                || !safeAccountId(requested)) {
            throw new OfficialQqSendException("INVALID_ACCOUNT_ID", "accountId 格式无效");
        }
        if (source == null) {
            throw new OfficialQqSendException("INVALID_SOURCE", "source 不能为空");
        }
        try {
            QqOfficialSourceKeyer.validateOfficialSource(source);
        } catch (RuntimeException invalid) {
            throw new OfficialQqSendException("INVALID_SOURCE", "source 必须是官方群或单聊来源");
        }
        if (!QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())
                && !QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(source.platform())) {
            throw new OfficialQqSendException(
                    "INVALID_SOURCE", "官方文本发送只支持群聊或单聊来源");
        }
        if (!requested.equals(source.accountId())) {
            throw new OfficialQqSendException(
                    "SOURCE_ACCOUNT_MISMATCH", "accountId 与 source 的账号不一致");
        }
        OfficialQqSendAccount account;
        try {
            Optional<OfficialQqSendAccount> resolved = accounts.resolve(requested);
            if (resolved == null || resolved.isEmpty()) {
                throw new OfficialQqSendException(
                        "ACCOUNT_NOT_FOUND", "未找到已配置的官方账号");
            }
            account = resolved.get();
        } catch (OfficialQqSendException notFound) {
            throw notFound;
        } catch (RuntimeException failure) {
            throw new OfficialQqSendException("ACCOUNT_LOOKUP_FAILED", "读取官方账号配置失败");
        }
        if (account == null || !requested.equals(account.accountId())) {
            throw new OfficialQqSendException("ACCOUNT_MISMATCH", "账号解析结果不匹配");
        }
        if (!account.enabled()) {
            throw new OfficialQqSendException("ACCOUNT_DISABLED", "官方账号未启用");
        }
        if (account.transport() == null
                || !requested.equals(account.transport().accountId())) {
            throw new OfficialQqSendException(
                    "ACCOUNT_TRANSPORT_MISMATCH", "账号与 transport 绑定不一致");
        }
        return account;
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

    private static String requireText(String text) throws OfficialQqSendException {
        if (text == null || text.isBlank()) {
            throw new OfficialQqSendException("INVALID_ARGUMENT", "text 不能为空");
        }
        return text;
    }

    private void requireReplyableEvent(String accountId, SourceAddress source, String eventId)
            throws OfficialQqSendException {
        if (eventReferences == null) {
            throw new OfficialQqSendException(
                    "EVENT_ID_REPLY_UNSUPPORTED", "当前不支持仅用 event_id 的被动回复");
        }
        try {
            eventReferences.requireReplyable(accountId, source, eventId);
        } catch (OfficialQqEventReferenceException failure) {
            throw new OfficialQqSendException(
                    eventReferenceErrorCode(failure.code()), eventReferenceSafeMessage(failure.code()));
        } catch (RuntimeException failure) {
            throw new OfficialQqSendException(
                    "EVENT_REFERENCE_LOOKUP_FAILED", "官方事件引用校验失败");
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

    private void requireInboundSameSource(SourceAddress source, String msgId)
            throws OfficialQqSendException {
        Optional<Message> found;
        try {
            found = board.findByExternalMessageId(source, msgId);
        } catch (RuntimeException failure) {
            throw new OfficialQqSendException("BOARD_READ_FAILED", "读取消息板失败");
        }
        if (found.isEmpty()
                || found.get().inbound().direction() != Direction.INBOUND
                || !msgId.equals(found.get().inbound().messageId())) {
            throw new OfficialQqSendException(
                    "REPLY_TARGET_NOT_FOUND", "被动回复的 msg_id 不是该来源的真实入站消息");
        }
    }

    private ProviderResult dispatch(OfficialQqSendTransport transport, SourceAddress source,
                                    OfficialQqSendMessage message) {
        try {
            String externalId = switch (source.platform()) {
                case QqOfficialSourceKeyer.GROUP_PLATFORM ->
                        transport.sendGroup(source.lastSegment(), message);
                case QqOfficialSourceKeyer.PRIVATE_PLATFORM ->
                        transport.sendUser(source.lastSegment(), message);
                default -> throw new IllegalArgumentException("不是官方消息来源");
            };
            String safeId = safeExternalId(externalId);
            if (safeId.isEmpty()) {
                return new ProviderResult(
                        DeliveryStatus.UNKNOWN, "", "MALFORMED_RESPONSE", true);
            }
            return new ProviderResult(DeliveryStatus.SENT, safeId, null, true);
        } catch (OfficialQqTransportException typed) {
            if (typed.dispatched()) {
                return new ProviderResult(
                        DeliveryStatus.UNKNOWN, "", typed.errorCode(), true);
            }
            return new ProviderResult(
                    DeliveryStatus.FAILED_BEFORE_SEND, "", typed.errorCode(), false);
        } catch (Exception failure) {
            return new ProviderResult(DeliveryStatus.UNKNOWN, "", providerError(failure), true);
        }
    }

    private OfficialQqSendOutcome finish(AppendResult begun, String operationId, int partIndex,
                                          int partTotal, ProviderResult provider) {
        long localSeq = begun.message().localSeq();
        boolean recorded;
        try {
            recorded = board.finishOutbound(localSeq, provider.status(),
                    provider.externalMessageId(), provider.errorCode());
        } catch (RuntimeException failure) {
            recorded = false;
        }
        if (recorded) {
            return new OfficialQqSendOutcome(operationId, partIndex, partTotal, localSeq,
                    provider.status(), null, provider.externalMessageId(), provider.errorCode(),
                    true, provider.dispatched());
        }
        return new OfficialQqSendOutcome(operationId, partIndex, partTotal, localSeq,
                DeliveryStatus.UNKNOWN, provider.status(), "", BOARD_WRITE_FAILED,
                false, provider.dispatched());
    }

    private OfficialQqSendOutcome finishBeforeDispatch(
            AppendResult begun, String operationId, int partIndex, int partTotal,
            String errorCode) {
        ProviderResult localFailure = new ProviderResult(
                DeliveryStatus.FAILED_BEFORE_SEND, "", errorCode, false);
        return finish(begun, operationId, partIndex, partTotal, localFailure);
    }

    private OfficialQqSendOutcome boardWriteFailure(
            String operationId, int partIndex, int partTotal) {
        return new OfficialQqSendOutcome(operationId, partIndex, partTotal, null,
                DeliveryStatus.FAILED_BEFORE_SEND, null, "", BOARD_WRITE_FAILED,
                false, false);
    }

    private static String providerError(Exception failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof OfficialQqApiException api) {
            return switch (api.kind()) {
                case TIMEOUT -> TIMEOUT;
                case NETWORK -> "NETWORK";
                case AUTHENTICATION -> "AUTHENTICATION";
                case PERMISSION -> "PERMISSION";
                case NOT_FOUND -> "NOT_FOUND";
                case RATE_LIMITED -> "RATE_LIMITED";
                case SERVER -> "SERVER";
                case MALFORMED_RESPONSE -> "MALFORMED_RESPONSE";
                case HTTP, BUSINESS -> "PROVIDER_NONZERO";
                case CONFIGURATION -> "CONFIGURATION";
            };
        }
        if (cause instanceof TimeoutException) {
            return TIMEOUT;
        }
        return PROVIDER_EXCEPTION;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null
                && (current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? failure : current;
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

    private record ProviderResult(DeliveryStatus status, String externalMessageId,
                                  String errorCode, boolean dispatched) {

        private ProviderResult {
            Objects.requireNonNull(status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException("provider 结果必须是终止状态");
            }
        }
    }
}

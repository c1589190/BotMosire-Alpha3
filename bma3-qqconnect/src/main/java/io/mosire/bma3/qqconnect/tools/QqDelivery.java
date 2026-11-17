package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.bma3.core.message.Delivery;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared, package-private outbound attempt helpers: persist PENDING before dispatch, classify the
 * provider outcome conservatively, and atomically persist the terminal state and external id.
 *
 * <p>Classification rules:</p>
 * <ul>
 *   <li>A successful provider reply is {@link DeliveryStatus#SENT}; a missing external id is fine.</li>
 *   <li>Timeout, connection failure, post-dispatch exception and every non-zero/unknown provider
 *       reply are {@link DeliveryStatus#UNKNOWN}: none of them proves the platform did not act.</li>
 *   <li>{@link DeliveryStatus#FAILED_BEFORE_SEND} is only produced by
 *       {@link #failedBeforeSend(String)} or {@link #boardWriteFailure(String, int, int)}, whose
 *       caller must have proof that the request was never handed to the platform.</li>
 * </ul>
 *
 * <p>Error codes written to the board or returned as diagnostics are bounded uppercase categories;
 * raw provider messages and exception text never leave this class.</p>
 */
final class QqDelivery {

    /** Bounded waits. A timeout never cancels remote work and is never a resend signal. */
    static final Duration TEXT_WAIT = Duration.ofSeconds(5);
    static final Duration ATTACHMENT_WAIT = Duration.ofSeconds(60);

    static final String TIMEOUT = "TIMEOUT";
    static final String PROVIDER_NONZERO = "PROVIDER_NONZERO";
    static final String PROVIDER_EXCEPTION = "PROVIDER_EXCEPTION";
    static final String NOT_DISPATCHED = "NOT_DISPATCHED";
    static final String BOARD_WRITE_FAILED = "BOARD_WRITE_FAILED";

    private QqDelivery() {
    }

    /** One already-persisted PENDING board row. */
    record Pending(long localSeq, String operationId, int partIndex, int partTotal) {

        Pending {
            Objects.requireNonNull(operationId, "operationId");
            if (localSeq < 0 || partIndex < 1 || partTotal < 1 || partIndex > partTotal) {
                throw new IllegalArgumentException("非法出站分片元数据");
            }
        }
    }

    /** What the external provider reported, before it is persisted. */
    record ProviderResult(DeliveryStatus status, String externalMessageId, String errorCode,
                          int retcode) {

        ProviderResult {
            Objects.requireNonNull(status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException("provider 结果必须是终止状态: " + status);
            }
            externalMessageId = externalMessageId == null ? "" : externalMessageId.trim();
            errorCode = Delivery.normalizeErrorCode(errorCode);
            if (!externalMessageId.isEmpty() && status != DeliveryStatus.SENT) {
                throw new IllegalArgumentException("只有 SENT 可以携带外部消息 id");
            }
            if (status == DeliveryStatus.SENT && errorCode != null) {
                throw new IllegalArgumentException("SENT 不能携带错误类别");
            }
            if (status == DeliveryStatus.FAILED_BEFORE_SEND && errorCode == null) {
                throw new IllegalArgumentException("FAILED_BEFORE_SEND 必须给出可证明的错误类别");
            }
        }
    }

    /**
     * One tool-facing part result.
     *
     * <p>{@code status} is the conservative state exposed to the tool caller. When
     * {@code recorded == false} the board is the source of truth and still holds PENDING (or has no
     * row at all for a failed initial INSERT); the outward status is therefore never a durable
     * success. {@code providerState} is the provider's own report and is exposed only when it must
     * be distinguished from that conservative status. {@code dispatched} is present only for
     * failure diagnostics: {@code false} means no provider request was made for this part.</p>
     */
    record Outcome(String operationId, int partIndex, int partTotal, Long localSeq,
                   DeliveryStatus status, DeliveryStatus providerState,
                   String externalMessageId, String errorCode, int retcode,
                   boolean recorded, Boolean dispatched) {

        Outcome {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(status, "status");
            if (partIndex < 1 || partTotal < 1 || partIndex > partTotal) {
                throw new IllegalArgumentException("非法出站分片元数据");
            }
            if (localSeq != null && localSeq < 0) {
                throw new IllegalArgumentException("localSeq 不能为负");
            }
            if (localSeq == null && (recorded || status != DeliveryStatus.FAILED_BEFORE_SEND)) {
                throw new IllegalArgumentException("没有板上行时只能报告未派发的板写失败");
            }
        }
    }

    /**
     * Persists the PENDING row for one actual part before any provider call.
     *
     * @throws IllegalStateException when the board write fails: the caller must not dispatch
     */
    static Pending begin(QqToolContext context, InboundMessage message, String operationId,
                         int partIndex, int partTotal) {
        Objects.requireNonNull(message, "message");
        var stored = context.board().beginOutbound(message, operationId, partIndex, partTotal);
        return new Pending(stored.message().localSeq(), operationId, partIndex, partTotal);
    }

    /**
     * Sends one request with a bounded wait and classifies the reply. The wait stops local
     * waiting only; a late provider completion has no callback and can never rewrite the board.
     */
    static ProviderResult dispatch(OneBot11Client client, String action,
                                   Map<String, Object> params, Duration wait) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(wait, "wait");
        JsonNode response;
        try {
            response = client.sendAction(action, params)
                    .orTimeout(wait.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (RuntimeException failure) {
            return new ProviderResult(DeliveryStatus.UNKNOWN, "", failureCode(failure), -1);
        }
        int retcode = response == null ? -1 : response.path("retcode").asInt(-1);
        boolean ok = response != null
                && "ok".equals(response.path("status").asText())
                && retcode == 0;
        if (!ok) {
            // A non-zero/absent reply code does not prove that the platform did not act.
            return new ProviderResult(DeliveryStatus.UNKNOWN, "", PROVIDER_NONZERO, retcode);
        }
        return new ProviderResult(DeliveryStatus.SENT, externalId(response), null, retcode);
    }

    /**
     * Explicit {@link DeliveryStatus#FAILED_BEFORE_SEND} outcome. Only call this when the caller
     * can prove no provider side effect happened (never from a timeout or ambiguous async failure).
     */
    static ProviderResult failedBeforeSend(String errorCode) {
        return new ProviderResult(DeliveryStatus.FAILED_BEFORE_SEND, "",
                errorCode == null || errorCode.isBlank() ? NOT_DISPATCHED : errorCode, -1);
    }

    /**
     * Atomically writes the terminal status and external id through the board's single update.
     *
     * <p>Any failure (exception or {@code false} from the board) is reported as non-durable: the
     * outward status becomes {@link DeliveryStatus#UNKNOWN} with the bounded
     * {@link #BOARD_WRITE_FAILED} category, and the provider's own report is kept separately in
     * {@code providerState}. The unpersisted external id is not exposed as {@code messageId}.</p>
     */
    static Outcome finish(QqToolContext context, Pending pending, ProviderResult result) {
        boolean recorded;
        try {
            recorded = context.board().finishOutbound(pending.localSeq(), result.status(),
                    result.externalMessageId(), result.errorCode());
        } catch (RuntimeException failure) {
            recorded = false;
        }
        if (recorded) {
            return new Outcome(pending.operationId(), pending.partIndex(), pending.partTotal(),
                    pending.localSeq(), result.status(), null, result.externalMessageId(),
                    result.errorCode(), result.retcode(), true,
                    result.status() == DeliveryStatus.FAILED_BEFORE_SEND ? false : null);
        }
        return new Outcome(pending.operationId(), pending.partIndex(), pending.partTotal(),
                pending.localSeq(), DeliveryStatus.UNKNOWN, result.status(), "",
                BOARD_WRITE_FAILED, result.retcode(), false,
                result.status() != DeliveryStatus.FAILED_BEFORE_SEND);
    }

    /**
     * Diagnostic for a part whose initial PENDING write failed: no board row was created and the
     * provider was not called for this part. Earlier parts keep their own outcomes.
     */
    static Outcome boardWriteFailure(String operationId, int partIndex, int partTotal) {
        return new Outcome(operationId, partIndex, partTotal, null,
                DeliveryStatus.FAILED_BEFORE_SEND, null, "", BOARD_WRITE_FAILED, -1,
                false, false);
    }

    /** Tool-facing diagnostics: bounded fields only, never provider messages or exception text. */
    static ObjectNode json(Outcome outcome) {
        ObjectNode node = QqToolSupport.object();
        if (outcome.localSeq() == null) {
            node.putNull("localSeq");
        } else {
            node.put("localSeq", outcome.localSeq());
        }
        node.put("operationId", outcome.operationId());
        node.put("partIndex", outcome.partIndex());
        node.put("partTotal", outcome.partTotal());
        node.put("state", outcome.status().name());
        if (outcome.providerState() != null) {
            node.put("providerState", outcome.providerState().name());
        }
        node.put("messageId", outcome.externalMessageId());
        node.put("retcode", outcome.retcode());
        if (outcome.errorCode() != null) {
            node.put("errorCode", outcome.errorCode());
        }
        node.put("recorded", outcome.recorded());
        if (outcome.dispatched() != null) {
            node.put("dispatched", outcome.dispatched());
        }
        if (BOARD_WRITE_FAILED.equals(outcome.errorCode())) {
            // The board could not record this attempt; no blind resend of the operation.
            node.put("resendAllowed", false);
        }
        return node;
    }

    private static String failureCode(RuntimeException failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause instanceof TimeoutException ? TIMEOUT : PROVIDER_EXCEPTION;
    }

    private static String externalId(JsonNode response) {
        String id = response.path("data").path("message_id").asText("");
        if (id.isBlank()) {
            // File uploads answer with file_id rather than message_id; both identify the send.
            id = response.path("data").path("file_id").asText("");
        }
        return id.isBlank() ? "" : id.trim();
    }
}

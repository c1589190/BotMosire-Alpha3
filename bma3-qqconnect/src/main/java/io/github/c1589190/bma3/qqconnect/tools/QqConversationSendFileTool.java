package io.github.c1589190.bma3.qqconnect.tools;

import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a file over the OneBot file-upload actions.
 *
 * <p>BMA3 only has the OneBot WS endpoint; it does not run a file server and does not assume a
 * shared filesystem (D-047). Three transport modes exist, in order:</p>
 * <ol>
 *   <li>{@code filePath} under the data directory → bytes are sent as {@code base64://};</li>
 *   <li>the same bytes as an absolute path (works when LLBot shares the host filesystem);</li>
 *   <li>{@code providerPath} — a path the Agent has already placed on the QQ host via bash/SSH.</li>
 * </ol>
 *
 * <p>Every transport attempt is persisted as a {@code PENDING} outbound row before the request can
 * reach the provider, and its terminal state plus external id are written atomically. A fallback
 * transport is attempted only after the previous attempt was <em>proven</em> not to have been
 * dispatched (for example, the local client is already disconnected). A timeout, connection loss,
 * exception or non-zero/unknown provider reply is {@code UNKNOWN}: it stops every further transport
 * and the optional associated text. The associated text is sent only after the file row is durably
 * {@code SENT}.</p>
 *
 * <p>Group chats use {@code upload_group_file}, private chats {@code upload_private_file}. A
 * providerPath-only call keeps the original use: no local file is needed and no fallback is
 * attempted.</p>
 */
public final class QqConversationSendFileTool implements AgentTool {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final QqToolContext context;

    public QqConversationSendFileTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_send_file";
    }

    @Override
    public String description() {
        return "向 source 发送文件（群=upload_group_file，私聊=upload_private_file）。"
                + "filePath 必须在 data 目录下，先 base64 再试绝对路径；若 LLBot 在另一台机器，"
                + "可用 bash 把文件放到 QQ 机后用 providerPath 传路径。每次实际派发前先落 PENDING，"
                + "超时/断线/未知响应=UNKNOWN 且不再尝试任何后备路径或关联文本；"
                + "只有可证明未派发时才允许换下一种方式。"
                + "关联文本仅在文件状态耐久 SENT 后发送。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "filePath", Map.of("type", "string",
                        "description", "BMA3 本地路径，必须位于 data 目录下"),
                "providerPath", Map.of("type", "string",
                        "description", "已在 QQ 机上的路径（Agent 用 bash 放置后传入）"),
                "fileName", Map.of("type", "string"),
                "text", Map.of("type", "string", "description", "可选：文件耐久 SENT 后发送的文本")),
                "required", List.of("source"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            String filePath = textArg(toolContext, "filePath");
            String providerPath = textArg(toolContext, "providerPath");
            if (filePath.isBlank() && providerPath.isBlank()) {
                throw new IllegalArgumentException("filePath 与 providerPath 至少提供一个");
            }
            String fileName = textArg(toolContext, "fileName");
            byte[] bytes = null;
            String base64 = "";
            if (!filePath.isBlank()) {
                Path path = localPath(filePath);
                long size = Files.size(path);
                if (size <= 0 || size > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("INVALID_ARGUMENT: 文件大小必须在 1B..10MB");
                }
                bytes = Files.readAllBytes(path);
                base64 = "base64://" + Base64.getEncoder().encodeToString(bytes);
                if (fileName.isBlank()) {
                    fileName = path.getFileName().toString();
                }
            }
            if (fileName.isBlank()) {
                fileName = Path.of(providerPath).getFileName().toString();
            }
            // Validate the dependent text before any file side effect, so an invalid caption can
            // never leave a filed upload behind that the caller then retries.
            String text = textArg(toolContext, "text");
            if (!text.isBlank()) {
                QqSendSupport.validateText(text, context);
            }

            String action = "qq_group".equals(source.platform())
                    ? "upload_group_file" : "upload_private_file";
            QqSendSupport.SendRoute route = QqSendSupport.route(source, action, Map.of());
            Map<String, Object> baseParams = route.targetParams();
            OneBot11Client client = QqToolSupport.client(context, source);

            List<Candidate> candidates = new ArrayList<>();
            if (!base64.isBlank()) {
                candidates.add(new Candidate("base64", base64));
            }
            if (bytes != null && !filePath.isBlank()) {
                candidates.add(new Candidate("absolutePath",
                        localPath(filePath).toAbsolutePath().toString()));
            }
            if (!providerPath.isBlank()) {
                candidates.add(new Candidate("providerPath", providerPath.trim()));
            }

            var self = context.accounts().self(source.accountId())
                    .orElse(new io.github.c1589190.bma3.qqconnect.account.AccountRegistry.SelfIdentity("", ""));
            // A file transfer candidate is an independent send attempt, not a text chunk: each
            // candidate gets its own server-generated operation and 1/1 part metadata. The tool
            // result's ordered attempts array keeps the same-call relation without pretending the
            // candidates are parts of one file (spec §4.2's part model is for text chunks).
            List<QqDelivery.Outcome> outcomes = new ArrayList<>();
            List<String> attempted = new ArrayList<>();
            QqDelivery.Outcome finalOutcome = null;
            for (int index = 0; index < candidates.size(); index++) {
                Candidate candidate = candidates.get(index);
                InboundMessage outbound = new InboundMessage(
                        source, "out-file-" + UUID.randomUUID(), Instant.now(),
                        List.of(new Segment("file", fileName, Map.of("name", fileName))),
                        Direction.OUTBOUND, self.userId(), self.nickname());
                String attemptOperationId = "op-" + UUID.randomUUID();
                QqDelivery.Pending pending;
                try {
                    pending = QqDelivery.begin(context, outbound, attemptOperationId, 1, 1);
                } catch (RuntimeException failure) {
                    // No row was created and no provider request was made for this attempt.
                    finalOutcome = QqDelivery.boardWriteFailure(attemptOperationId, 1, 1);
                    outcomes.add(finalOutcome);
                    break;
                }
                attempted.add(candidate.via());
                Map<String, Object> params = new LinkedHashMap<>(baseParams);
                params.put("file", candidate.file());
                params.put("name", fileName);
                if (baseParams.containsKey("group_id")) {
                    params.put("folder", "/");
                }
                // connected()==false proves this request is not handed to the platform: the
                // WebSocket client rejects locally without a network side effect. Anything after a
                // real dispatch is UNKNOWN, never a fallback signal.
                QqDelivery.ProviderResult providerResult = client.connected()
                        ? QqDelivery.dispatch(client, action, params, QqDelivery.ATTACHMENT_WAIT)
                        : QqDelivery.failedBeforeSend(QqDelivery.NOT_DISPATCHED);
                QqDelivery.Outcome outcome = QqDelivery.finish(context, pending, providerResult);
                outcomes.add(outcome);
                finalOutcome = outcome;
                if (outcome.status() == DeliveryStatus.SENT && outcome.recorded()) {
                    break;
                }
                if (outcome.status() != DeliveryStatus.FAILED_BEFORE_SEND || !outcome.recorded()) {
                    // UNKNOWN may have reached the platform; a terminal write failure is not
                    // durable. Neither may produce another path or the dependent text.
                    break;
                }
            }
            if (finalOutcome == null) {
                finalOutcome = QqDelivery.boardWriteFailure("op-" + UUID.randomUUID(), 1, 1);
                outcomes.add(finalOutcome);
            }

            String textState = "";
            String textErrorCode = null;
            List<QqDelivery.Outcome> textOutcomes = List.of();
            if (!text.isBlank()) {
                if (finalOutcome.status() == DeliveryStatus.SENT && finalOutcome.recorded()) {
                    try {
                        textOutcomes = QqSendSupport.sendParts(context, source, text, "",
                                "qq_group".equals(source.platform())
                                        ? "send_group_msg" : "send_private_msg", Map.of());
                        textState = aggregateTextState(textOutcomes);
                    } catch (RuntimeException failure) {
                        // sendParts converts board/dispatch failures into outcomes; an escaping
                        // exception happened before it called the text provider. The file is
                        // already durably SENT, so keep that fact and the no-reupload contract
                        // instead of falling through to the generic outer catch.
                        textState = DeliveryStatus.FAILED_BEFORE_SEND.name();
                        textErrorCode = "TEXT_SEND_FAILED";
                    }
                } else {
                    textState = "NOT_SENT";
                }
            }
            boolean boardWriteFailed = outcomes.stream().anyMatch(outcome ->
                    QqDelivery.BOARD_WRITE_FAILED.equals(outcome.errorCode()))
                    || textOutcomes.stream().anyMatch(outcome ->
                    QqDelivery.BOARD_WRITE_FAILED.equals(outcome.errorCode()));
            boolean attachmentUnknown = finalOutcome.status() == DeliveryStatus.UNKNOWN;
            boolean textUnknown = DeliveryStatus.UNKNOWN.name().equals(textState)
                    || textOutcomes.stream().anyMatch(outcome ->
                    outcome.status() == DeliveryStatus.UNKNOWN);
            boolean resendForbidden = boardWriteFailed || attachmentUnknown || textUnknown
                    || textErrorCode != null;

            var root = QqDelivery.json(finalOutcome);
            root.put("externalMessageId", finalOutcome.externalMessageId());
            root.put("action", action);
            root.put("fileName", fileName);
            root.put("bytes", bytes == null ? 0 : bytes.length);
            root.put("via", attempted.isEmpty() ? "" : attempted.getLast());
            root.put("attempted", String.join(",", attempted));
            var attemptsNode = root.putArray("attempts");
            outcomes.forEach(outcome -> attemptsNode.add(QqDelivery.json(outcome)));
            if (!text.isBlank()) {
                root.put("textState", textState);
                var textPartsNode = root.putArray("textParts");
                textOutcomes.forEach(outcome -> textPartsNode.add(QqDelivery.json(outcome)));
            }
            if (textErrorCode != null) {
                root.put("textErrorCode", textErrorCode);
            }
            if (resendForbidden) {
                // Any unrecorded or uncertain part forbids a blind resend of the attachment.
                root.put("resendAllowed", false);
            }
            String hint = null;
            if (textErrorCode != null) {
                hint = "文件已发送（localSeq=" + finalOutcome.localSeq()
                        + "）；关联文本未发送；请勿重新上传文件，仅按需人工补发文本";
            } else if (boardWriteFailed && finalOutcome.status() == DeliveryStatus.SENT
                    && finalOutcome.recorded()) {
                hint = "文件已发送；关联文本未能可靠落板；禁止重新上传文件";
            } else if (boardWriteFailed) {
                hint = "结果未能可靠落板；禁止重发或重新上传文件";
            } else if (attachmentUnknown) {
                hint = text.isBlank()
                        ? "附件状态不确定，可能已发送；禁止自动重发"
                        : "附件状态不确定，可能已发送；关联文本未发送；禁止自动重发或重新上传";
            } else if (textUnknown) {
                hint = "文件已发送；关联文本状态不确定，可能已发送；禁止自动重发或重新上传文件";
            } else if (finalOutcome.status() == DeliveryStatus.FAILED_BEFORE_SEND) {
                hint = "未派发（例如 provider 未连接）；状态已落板，确认连接后可人工重试";
            }
            if (hint != null) {
                root.put("hint", hint);
            }
            String json = root.toString();
            if (textErrorCode != null) {
                return ToolResult.error(textErrorCode, json);
            }
            return boardWriteFailed
                    ? ToolResult.error(QqDelivery.BOARD_WRITE_FAILED, json)
                    : ToolResult.ok(json);
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (Exception failure) {
            return ToolResult.error("FILE_SEND_FAILED", failure.getClass().getSimpleName());
        }
    }

    /**
     * Bounded summary of the dependent text parts. A board write failure is surfaced separately so
     * the caller can never mistake the overall call for a durable success.
     */
    private static String aggregateTextState(List<QqDelivery.Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return "NOT_SENT";
        }
        if (outcomes.stream().anyMatch(outcome ->
                QqDelivery.BOARD_WRITE_FAILED.equals(outcome.errorCode()))) {
            return QqDelivery.BOARD_WRITE_FAILED;
        }
        if (outcomes.stream().allMatch(outcome ->
                outcome.status() == DeliveryStatus.SENT && outcome.recorded())) {
            return "SENT";
        }
        return outcomes.getLast().status().name();
    }

    private record Candidate(String via, String file) {
    }

    private Path localPath(String raw) {
        Path root = context.imageRoot().toAbsolutePath().normalize();
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT: 文件必须位于 " + root);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT: 文件不存在");
        }
        return path;
    }

    private static String textArg(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value.trim() : "";
    }
}

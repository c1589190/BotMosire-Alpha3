package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
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
 * shared filesystem (D-047). Three modes, in order:</p>
 * <ol>
 *   <li>{@code filePath} under the data directory → bytes are sent as {@code base64://};</li>
 *   <li>the same bytes as an absolute path (works when LLBot shares the host filesystem);</li>
 *   <li>{@code providerPath} — a path the Agent has already placed on the QQ host via bash/SSH.</li>
 * </ol>
 * <p>Group chats use {@code upload_group_file}, private chats {@code upload_private_file}. An
 * unsupported provider returns {@code UNSUPPORTED_REMOTE_FILE}; {@code UNKNOWN} is never retried.</p>
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
                + "可用 bash 把文件放到 QQ 机后用 providerPath 传路径。三态返回，UNKNOWN 不重试。";
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
                "text", Map.of("type", "string", "description", "可选：先发一条文本")),
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
            String action = "qq_group".equals(source.platform())
                    ? "upload_group_file" : "upload_private_file";
            Map<String, Object> baseParams = targetParams(source);

            var stored = context.board().append(new InboundMessage(
                    source, "out-file-" + UUID.randomUUID(), Instant.now(),
                    List.of(new Segment("file", fileName, Map.of("name", fileName))),
                    Direction.OUTBOUND,
                    context.accounts().self(source.accountId())
                            .map(io.mosire.bma3.qqconnect.account.AccountRegistry.SelfIdentity::userId)
                            .orElse(""),
                    context.accounts().self(source.accountId())
                            .map(io.mosire.bma3.qqconnect.account.AccountRegistry.SelfIdentity::nickname)
                            .orElse("")));

            OneBot11Client client = QqToolSupport.client(context, source);
            List<String> attempted = new ArrayList<>();
            String state = "FAILED_BEFORE_SEND";
            String via = "";
            String externalId = "";
            if (!base64.isBlank()) {
                attempted.add("base64");
                var attempt = upload(client, action, baseParams, base64, fileName);
                state = attempt.state();
                externalId = attempt.externalId();
                via = "base64";
            }
            if (!"SENT".equals(state) && bytes != null && !filePath.isBlank()) {
                attempted.add("absolutePath");
                var attempt = upload(client, action, baseParams,
                        localPath(filePath).toAbsolutePath().toString(), fileName);
                state = attempt.state();
                externalId = attempt.externalId();
                via = "absolutePath";
            }
            if (!"SENT".equals(state) && !providerPath.isBlank()) {
                attempted.add("providerPath");
                var attempt = upload(client, action, baseParams, providerPath.trim(), fileName);
                state = attempt.state();
                externalId = attempt.externalId();
                via = "providerPath";
            }
            if ("FAILED_BEFORE_SEND".equals(state) && !providerPath.isBlank()
                    && !"SENT".equals(state)) {
                state = "UNSUPPORTED_REMOTE_FILE";
            }
            if (!externalId.isBlank()) {
                context.board().attachExternalMessageId(stored.message().localSeq(), externalId);
            }
            String text = textArg(toolContext, "text");
            String textStates = "";
            if (!text.isBlank()) {
                QqSendSupport.validateText(text, context);
                textStates = QqSendSupport.sendText(context, source, text, "",
                        "qq_group".equals(source.platform())
                                ? "send_group_msg" : "send_private_msg");
            }
            var root = QqToolSupport.object();
            root.put("state", state);
            root.put("action", action);
            root.put("localSeq", stored.message().localSeq());
            root.put("externalMessageId", externalId);
            root.put("fileName", fileName);
            root.put("bytes", bytes == null ? 0 : bytes.length);
            root.put("via", via);
            root.put("attempted", String.join(",", attempted));
            if (!textStates.isBlank()) {
                root.put("textState", textStates);
            }
            if ("UNSUPPORTED_REMOTE_FILE".equals(state)) {
                root.put("hint", "provider 不接受 base64/绝对路径；用 bash 把文件放到 QQ 机后传 providerPath");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (Exception failure) {
            return ToolResult.error("FILE_SEND_FAILED", failure.getClass().getSimpleName());
        }
    }

    private record Attempt(String state, String externalId) {
    }

    private Attempt upload(OneBot11Client client, String action, Map<String, Object> baseParams,
                           String file, String fileName) {
        Map<String, Object> params = new LinkedHashMap<>(baseParams);
        params.put("file", file);
        params.put("name", fileName);
        if (baseParams.containsKey("group_id")) {
            params.put("folder", "/");
        }
        try {
            JsonNode response = client.sendAction(action, params).join();
            boolean ok = "ok".equals(response.path("status").asText())
                    && response.path("retcode").asInt(-1) == 0;
            String externalId = response.path("data").path("message_id").asText("");
            if (externalId.isBlank()) {
                externalId = response.path("data").path("file_id").asText("");
            }
            return new Attempt(ok ? "SENT" : "FAILED_BEFORE_SEND", externalId);
        } catch (RuntimeException failure) {
            return new Attempt("UNKNOWN", "");
        }
    }

    private static Map<String, Object> targetParams(SourceAddress source) {
        Map<String, Object> params = new LinkedHashMap<>();
        if ("qq_group".equals(source.platform())) {
            params.put("group_id", Long.parseLong(source.segments().get(1)));
        } else {
            params.put("user_id", Long.parseLong(source.segments().get(1)));
        }
        return params;
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

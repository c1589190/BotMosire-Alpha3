package io.mosire.bma3.qqconnect.tools;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a local image (for example a login QR screenshot) as an OneBot image segment. The file
 * must live under the configured image root; bytes are sent as {@code base64://} so a remote
 * LLBot does not need to see the BMA3 filesystem.
 */
public final class QqConversationSendImageTool implements AgentTool {

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private final QqToolContext context;

    public QqConversationSendImageTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_send_image";
    }

    @Override
    public String description() {
        return "向 source 指定的 QQ 会话发送本地图片（如登录二维码截图）；图片必须位于 data 目录下，"
                + "以 base64 发送，避免云端 LLBot 路径不可见。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "imagePath", Map.of("type", "string"),
                "text", Map.of("type", "string")), "required", List.of("source", "imagePath"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            OneBot11Client client = QqToolSupport.client(context, source);
            Path image = imagePath(QqToolSupport.text(toolContext, "imagePath"));
            if (!Files.isRegularFile(image)) {
                throw new IllegalArgumentException("INVALID_ARGUMENT: 图片不存在");
            }
            long size = Files.size(image);
            if (size <= 0 || size > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("INVALID_ARGUMENT: 图片大小必须在 1B..8MB");
            }
            String text = text(toolContext);
            QqSendSupport.validateText(text, context);
            String encoded = "base64://" + Base64.getEncoder().encodeToString(Files.readAllBytes(image));
            List<Map<String, Object>> message = new ArrayList<>();
            if (!text.isBlank()) {
                message.add(Map.of("type", "text", "data", Map.of("text", text)));
            }
            message.add(Map.of("type", "image", "data", Map.of("file", encoded)));

            var stored = context.board().append(new InboundMessage(
                    source,
                    "out-image-" + UUID.randomUUID(),
                    Instant.now(),
                    List.of(new Segment("image", image.getFileName().toString())),
                    Direction.OUTBOUND));

            String action;
            Map<String, Object> params;
            if ("qq_group".equals(source.platform())) {
                action = "send_group_msg";
                params = Map.of("group_id", Long.parseLong(source.segments().get(1)),
                        "message", message);
            } else {
                action = "send_private_msg";
                params = Map.of("user_id", Long.parseLong(source.segments().get(1)),
                        "message", message);
            }
            String state;
            String externalId = "";
            try {
                var response = client.sendAction(action, params).join();
                boolean ok = "ok".equals(response.path("status").asText())
                        && response.path("retcode").asInt(-1) == 0;
                state = ok ? "SENT" : "FAILED_BEFORE_SEND";
                externalId = response.path("data").path("message_id").asText("");
            } catch (RuntimeException failure) {
                state = "UNKNOWN";
            }
            var root = QqToolSupport.object();
            root.put("state", state);
            root.put("localSeq", stored.message().localSeq());
            root.put("messageId", externalId);
            root.put("action", action);
            root.put("bytes", size);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (Exception failure) {
            return ToolResult.error("IMAGE_SEND_FAILED", "图片发送失败");
        }
    }

    private Path imagePath(String raw) {
        Path root = context.imageRoot().toAbsolutePath().normalize();
        Path image = Path.of(raw).toAbsolutePath().normalize();
        if (!image.startsWith(root)) {
            throw new IllegalArgumentException("INVALID_ARGUMENT: 图片必须位于 " + root);
        }
        return image;
    }

    private String text(ToolContext toolContext) {
        Object raw = toolContext.arguments().get("text");
        return raw instanceof String value ? value : "";
    }
}

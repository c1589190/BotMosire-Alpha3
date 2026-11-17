package io.mosire.bma3.qqofficial.media;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unregistered tool implementation for one direct local image/file send.
 *
 * <p>The tool schema deliberately accepts a local path but never returns it. Its result contains
 * only bounded state metadata; {@code file_info}, Base64 bytes, file contents and paths never enter
 * the MCP result.</p>
 */
public final class QqOfficialSendMediaTool implements AgentTool {

    private final OfficialQqMediaService service;
    private final int fileType;
    private final String name;

    public QqOfficialSendMediaTool(OfficialQqMediaService service, int fileType, String name) {
        this.service = Objects.requireNonNull(service, "service");
        if (fileType != 1 && fileType != 4) {
            throw new IllegalArgumentException("本工具只支持 fileType 1 或 4");
        }
        this.fileType = fileType;
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "向指定 QQ 官方账号与 raw source 单次发送 dataDir 内本地图片/文件；仅支持不超过 "
                + "5MiB 的直接 Base64 上传，超限需要分片但尚未实现；上传前先落消息板 PENDING，"
                + "上传或发送不确定=UNKNOWN 且不自动重发；真正执行发生在服务端，不暴露 file_info、"
                + "Base64、路径或正文。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "accountId", Map.of(
                                "type", "string",
                                "description", "已启用的 QQ 官方机器人账号 ID"),
                        "source", Map.of(
                                "type", "string",
                                "description", "qq_official_group/<account>/<group_openid> 或 "
                                        + "qq_official_private/<account>/<user_openid>"),
                        "path", Map.of(
                                "type", "string",
                                "description", "dataDir 内的常规非符号链接本地文件"),
                        "replyToMessageId", Map.of(
                                "type", "string",
                                "description", "可选；同 source 的真实入站官方 msg_id"),
                        "eventId", Map.of(
                                "type", "string",
                                "description", "可选；服务端已证明可回复的官方 event_id")),
                "required", List.of("accountId", "source", "path"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            String accountId = required(context, "accountId");
            String sourceRaw = required(context, "source");
            String path = required(context, "path");
            SourceAddress source;
            try {
                source = SourceAddress.parse(sourceRaw);
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_ARGUMENT", "source 格式无效");
            }
            String replyTo = optional(context, "replyToMessageId");
            String eventId = optional(context, "eventId");
            OfficialQqMediaOutcome outcome = fileType == 1
                    ? service.sendImage(accountId, source, path, replyTo, eventId)
                    : service.sendFile(accountId, source, path, replyTo, eventId);
            ObjectNode result = outcome.json();
            return ToolResult.ok(result.toString());
        } catch (OfficialQqMediaException failure) {
            return ToolResult.error(failure.code(), failure.safeMessage());
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENT", "媒体发送参数无效");
        } catch (RuntimeException failure) {
            return ToolResult.error("INTERNAL_ERROR", "官方媒体发送发生内部错误");
        }
    }

    private static String required(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String optional(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value : "";
    }
}

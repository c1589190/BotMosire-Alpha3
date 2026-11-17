package io.mosire.bma3.qqofficial.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrException;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrResult;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrService;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentTool for the runtime B share QR.
 *
 * <p>The caller must name a concrete official account. There is no default-account fallback: an
 * absent, blank or unsafe id is rejected before the resolver is touched. A successful result
 * contains only {@code url}, {@code pngPath} and {@code accountId}; token, Authorization and raw
 * upstream response text are never in the result.</p>
 */
public final class QqOfficialShareQrTool implements AgentTool {

    public static final String NAME = "qq_official_share_qr";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OfficialQqShareQrService service;

    public QqOfficialShareQrTool(OfficialQqShareQrService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "生成指定 QQ 官方机器人的分享/添加二维码 PNG；返回 url、pngPath、accountId。"
                + "accountId 必填，按具体账号 fail-closed，不返回 token 或原始响应。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "accountId", Map.of(
                                "type", "string",
                                "description", "已配置的 QQ 官方机器人账号 ID（必须精确指定）")),
                "required", List.of("accountId"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object rawAccountId = context.arguments().get("accountId");
        if (!(rawAccountId instanceof String accountId)) {
            return ToolResult.error("INVALID_ARGUMENT", "accountId 必须是非空字符串");
        }
        try {
            OfficialQqShareQrResult result = service.generate(accountId);
            ObjectNode output = JSON.createObjectNode();
            output.put("url", result.url());
            output.put("pngPath", result.pngPath().toString());
            output.put("accountId", result.accountId());
            return ToolResult.ok(output.toString());
        } catch (OfficialQqShareQrException failure) {
            return ToolResult.error(failure.code(), failure.safeMessage());
        } catch (RuntimeException failure) {
            // Do not surface exception text from PNG, filesystem or third-party libraries.
            return ToolResult.error("INTERNAL_ERROR", "生成分享二维码时发生内部错误");
        }
    }
}

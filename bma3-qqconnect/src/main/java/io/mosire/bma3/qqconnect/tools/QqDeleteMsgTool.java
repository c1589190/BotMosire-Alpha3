package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code qq_delete_msg}：撤回一条消息（OneBot {@code delete_msg}）。
 *
 * <p>用户报告（P2-4）：发了一条超长消息只能手写 WS 才撤掉。这里把那条路做成工具。
 *
 * <p><b>三态与其它发送工具同口径</b>：
 * <ul>
 *   <li>{@code DELETED} —— provider 明确成功；</li>
 *   <li>{@code FAILED} —— provider 明确拒绝（带 retcode 与 provider 的 message，常见：超过 2 分钟、
 *       不是自己发的、没有管理员权限、消息已过期）；</li>
 *   <li>{@code UNKNOWN} —— 超时/断线，<b>可能已经撤回</b>。调用方不得自动重试。</li>
 * </ul>
 *
 * <p>只改平台侧，不改消息板（消息板是事实源，撤回属于平台状态；要不要落标记是另一个设计决定）。
 */
public final class QqDeleteMsgTool implements AgentTool {

    private final QqToolContext context;

    public QqDeleteMsgTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_delete_msg";
    }

    @Override
    public String description() {
        return "撤回一条消息（OneBot delete_msg）。messageId 来自消息板读取工具或发送结果。"
                + "返回 {state: DELETED|FAILED|UNKNOWN, retcode, providerMessage}；"
                + "UNKNOWN 表示可能已撤回，禁止自动重试。平台限制通常为 2 分钟内且需要相应权限。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("accountId", Map.of("type", "string"));
        props.put("messageId", Map.of("type", "string",
                "description", "要撤回的消息 id（平台 message_id，不是消息板的 localSeq）"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("messageId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            String messageId = QqToolSupport.text(toolContext, "messageId");
            if (messageId.isBlank()) {
                throw new IllegalArgumentException("messageId 不能为空");
            }
            long numeric;
            try {
                numeric = Long.parseLong(messageId);
            } catch (NumberFormatException notNumeric) {
                throw new IllegalArgumentException("messageId 必须是平台消息号（数字）: " + messageId);
            }
            JsonNode response = QqToolSupport.client(context, accountId)
                    .sendAction("delete_msg", Map.of("message_id", numeric))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            int retcode = response.path("retcode").asInt(-1);
            String status = response.path("status").asText("");
            var root = QqToolSupport.object();
            root.put("messageId", messageId);
            root.put("retcode", retcode);
            root.put("providerStatus", status);
            root.put("providerMessage", response.path("message").asText(""));
            root.put("state", "ok".equals(status) && retcode == 0 ? "DELETED" : "FAILED");
            if (!"DELETED".equals(root.path("state").asText())) {
                root.put("hint", "provider 拒绝：常见原因是超过 2 分钟、不是自己发的、或缺少管理员权限");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            // 超时/断线：可能已经撤回，按三态给 UNKNOWN，绝不自动重试
            var root = QqToolSupport.object();
            root.put("state", "UNKNOWN");
            root.put("retcode", -1);
            root.put("providerMessage", failure.getCause() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getCause().getClass().getSimpleName());
            root.put("hint", "超时或连接失败：消息可能已经撤回，不要自动重试；先 qq_message_get 确认");
            return ToolResult.ok(root.toString());
        }
    }
}

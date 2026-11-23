package io.github.c1589190.bma3.app.onebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.config.AppConfig;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class CredentialsTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OneBotSettings settings;
    private final Supplier<AppConfig> current;

    public CredentialsTool(OneBotSettings settings, Supplier<AppConfig> current) {
        this.settings = settings;
        this.current = current;
    }

    @Override
    public String name() {
        return "bma3_credentials";
    }

    @Override
    public String description() {
        return "管理当前 BMA3 的 OneBot 凭据。list 列出引用，set 设置或替换，remove 删除，"
                + "import_windows_onebot 导入当前用户旧版 Windows 启动器保存的凭据。"
                + "在 set_config 的 qqconnect.accounts 中用 tokenRef 绑定引用。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string",
                        "enum", List.of("list", "set", "remove", "import_windows_onebot")),
                "name", Map.of("type", "string", "description", "OneBot 凭据引用名称"),
                "value", Map.of("type", "string", "description", "set 要保存的 OneBot access token")),
                "required", List.of("action"), "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> args = context.arguments();
        String action = text(args, "action");
        if ("list".equals(action)) {
            try {
                return ToolResult.ok(JSON.writeValueAsString(Map.of("credentials", settings.listCredentials())));
            } catch (IOException failure) {
                return unavailable();
            }
        }
        String name = text(args, "name");
        if (!OneBotCredentialStore.validName(name)) {
            return ToolResult.error("INVALID_ARGUMENT", "凭据名称须为 1 至 64 个数字、字母、点、下划线或连字符，且以数字或字母开头");
        }
        return switch (action) {
            case "set" -> save(name, text(args, "value"), OneBotCredentialStore.MANUAL);
            case "remove" -> change(name, null);
            case "import_windows_onebot" -> importWindows(name);
            default -> ToolResult.error("INVALID_ARGUMENT", "未知凭据操作");
        };
    }

    private ToolResult importWindows(String name) {
        if (!WindowsDpapi.available()) {
            return ToolResult.error("PLATFORM_UNSUPPORTED", "Windows OneBot DPAPI 导入需要 Windows");
        }
        try {
            return save(name, settings.importWindowsCredential(), OneBotCredentialStore.WINDOWS_IMPORT);
        } catch (NoSuchFileException missing) {
            return ToolResult.error("IMPORT_SOURCE_MISSING", "当前用户没有旧版 Windows OneBot DPAPI 凭据文件");
        } catch (IOException failure) {
            return ToolResult.error("IMPORT_FAILED", "无法读取或解密旧版 Windows OneBot DPAPI 凭据");
        }
    }

    private ToolResult save(String name, String token, String source) {
        if (!OneBotCredentialStore.validValue(token)) {
            return ToolResult.error("INVALID_ARGUMENT", "OneBot 凭据必须是非空且可用作 HTTP 请求头的文本");
        }
        return change(name, new OneBotCredentialStore.Entry(token, source));
    }

    private ToolResult change(String name, OneBotCredentialStore.Entry entry) {
        try {
            boolean changed = settings.changeCredential(name, entry, current);
            return ToolResult.ok(JSON.writeValueAsString(Map.of("name", name, "present", entry != null,
                    "changed", changed, "source", entry == null ? "" : entry.source())));
        } catch (IOException failure) {
            return unavailable();
        }
    }

    private static ToolResult unavailable() {
        return ToolResult.error("CREDENTIAL_STORE_UNAVAILABLE", "无法安全读取或更新 OneBot 凭据");
    }

    private static String text(Map<String, Object> args, String key) {
        return args.get(key) instanceof String value ? value : "";
    }
}

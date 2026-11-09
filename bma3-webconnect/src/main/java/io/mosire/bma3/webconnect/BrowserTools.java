package io.mosire.bma3.webconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Browser AgentTools. Refs come from browser_snapshot; text/screenshot are separate reads. */
public final class BrowserTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private BrowserTools() {
    }

    public static List<AgentTool> all(BrowserToolContext context) {
        return List.of(
                new OpenTool(context),
                new SnapshotTool(context),
                new ClickTool(context),
                new TypeTool(context),
                new TextTool(context),
                new ScreenshotTool(context),
                new BrowserVideoFramesTool(context));
    }

    abstract static class Base implements AgentTool {

        final BrowserToolContext context;

        Base(BrowserToolContext context) {
            this.context = context;
        }

        BrowserSession session() {
            return context.session().get();
        }

        ToolResult fail(RuntimeException failure) {
            String message = failure.getMessage() == null ? "browser error" : failure.getMessage();
            return ToolResult.error(message.startsWith("DOMAIN_NOT_ALLOWED")
                    || message.startsWith("INVALID_ARGUMENT") ? "INVALID_ARGUMENT" : "BROWSER_ERROR",
                    message);
        }

        String string(ToolContext toolContext, String name) {
            Object raw = toolContext.arguments().get(name);
            if (!(raw instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("INVALID_ARGUMENT: " + name + " 不能为空");
            }
            return value;
        }
    }

    private static final class OpenTool extends Base {

        OpenTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_open";
        }

        @Override
        public String description() {
            return "在无头 Chromium 中打开 http/https 页面；返回标题和当前 URL。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "url", Map.of("type", "string")), "required", List.of("url"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                String raw = string(toolContext, "url");
                URI uri = URI.create(raw);
                if (uri.getScheme() == null || uri.getHost() == null
                        || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                    throw new IllegalArgumentException("INVALID_ARGUMENT: 只允许 http/https URL");
                }
                if (!context.allowedHosts().isEmpty()
                        && context.allowedHosts().stream().noneMatch(host ->
                        uri.getHost().equals(host) || uri.getHost().endsWith("." + host))) {
                    throw new IllegalArgumentException("DOMAIN_NOT_ALLOWED: " + uri.getHost());
                }
                BrowserSession session = session();
                session.open(uri.toString());
                return ToolResult.ok(JSON.writeValueAsString(Map.of(
                        "url", session.currentUrl(),
                        "title", session.title())));
            } catch (IllegalArgumentException failure) {
                return fail(failure);
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }

    private static final class SnapshotTool extends Base {

        SnapshotTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_snapshot";
        }

        @Override
        public String description() {
            return "返回当前页面可见可交互元素的 ref、文本、链接和位置；用 ref 调 browser_click/type。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                return ToolResult.ok(JSON.writeValueAsString(session().snapshot()));
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }

    private static final class ClickTool extends Base {

        ClickTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_click";
        }

        @Override
        public String description() {
            return "点击 browser_snapshot 返回的 ref；会产生真实页面副作用。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "ref", Map.of("type", "string")), "required", List.of("ref"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                String ref = string(toolContext, "ref");
                if (!REF.matcher(ref).matches()) {
                    throw new IllegalArgumentException("INVALID_ARGUMENT: ref 非法");
                }
                BrowserSession session = session();
                session.click(ref);
                return ToolResult.ok(JSON.writeValueAsString(Map.of(
                        "clicked", ref,
                        "url", session.currentUrl(),
                        "title", session.title())));
            } catch (IllegalArgumentException failure) {
                return fail(failure);
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }

    private static final class TypeTool extends Base {

        TypeTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_type";
        }

        @Override
        public String description() {
            return "向 browser_snapshot 返回的输入元素写入文本。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "ref", Map.of("type", "string"),
                    "text", Map.of("type", "string")), "required", List.of("ref", "text"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                String ref = string(toolContext, "ref");
                if (!REF.matcher(ref).matches()) {
                    throw new IllegalArgumentException("INVALID_ARGUMENT: ref 非法");
                }
                String text = string(toolContext, "text");
                session().type(ref, text);
                return ToolResult.ok(JSON.writeValueAsString(Map.of("typed", ref)));
            } catch (IllegalArgumentException failure) {
                return fail(failure);
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }

    private static final class TextTool extends Base {

        TextTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_text";
        }

        @Override
        public String description() {
            return "返回当前页面可见文本（截断到 20000 字符）。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                String text = session().text();
                return ToolResult.ok(text.length() > 20000 ? text.substring(0, 20000) : text);
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }

    private static final class ScreenshotTool extends Base {

        ScreenshotTool(BrowserToolContext context) {
            super(context);
        }

        @Override
        public String name() {
            return "browser_screenshot";
        }

        @Override
        public String description() {
            return "保存当前页面 PNG 截图并返回本地路径；可用 read_image 查看。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            try {
                BrowserSession session = session();
                Path path = session.screenshot(context.screenshotDir());
                return ToolResult.ok(JSON.writeValueAsString(Map.of(
                        "path", path.toAbsolutePath().toString(),
                        "url", session.currentUrl(),
                        "title", session.title())));
            } catch (Exception failure) {
                return fail(new IllegalStateException(failure.getMessage(), failure));
            }
        }
    }
}

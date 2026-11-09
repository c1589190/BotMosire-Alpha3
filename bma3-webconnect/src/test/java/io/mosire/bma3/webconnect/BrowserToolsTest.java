package io.mosire.bma3.webconnect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserToolsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void opensAllowedPageAndOperatesSnapshotRefs() throws Exception {
        FakeSession session = new FakeSession();
        List<AgentTool> tools = BrowserTools.all(new BrowserToolContext(
                () -> session, tempDir, List.of("bilibili.com")));

        assertThat(tools).extracting(AgentTool::name).containsExactly(
                "browser_open", "browser_snapshot", "browser_click",
                "browser_type", "browser_text", "browser_screenshot",
                "browser_video_frames");

        ToolResult opened = tool(tools, "browser_open").execute(call(Map.of(
                "url", "https://www.bilibili.com/")));
        assertThat(opened.success()).isTrue();
        assertThat(mapper.readTree(opened.message()).path("title").asText())
                .isEqualTo("bilibili");

        ToolResult snapshot = tool(tools, "browser_snapshot").execute(call(Map.of()));
        assertThat(snapshot.message()).contains("bma3-1");

        assertThat(tool(tools, "browser_click").execute(call(Map.of("ref", "bma3-1")))
                .success()).isTrue();
        assertThat(session.clicked).isEqualTo("bma3-1");

        assertThat(tool(tools, "browser_type").execute(call(Map.of(
                "ref", "bma3-2", "text", "hello"))).success()).isTrue();
        assertThat(session.typed).isEqualTo("hello");

        ToolResult shot = tool(tools, "browser_screenshot").execute(call(Map.of()));
        Path path = Path.of(mapper.readTree(shot.message()).path("path").asText());
        assertThat(path).exists();
    }

    @Test
    void capturesVideoFramesAtRequestedTimes() throws Exception {
        FakeSession session = new FakeSession();
        List<AgentTool> tools = BrowserTools.all(new BrowserToolContext(
                () -> session, tempDir, List.of("bilibili.com")));
        ToolResult result = tool(tools, "browser_video_frames").execute(call(Map.of(
                "url", "https://www.bilibili.com/video/BV1xx",
                "times", List.of(1, "00:02"))));
        assertThat(result.success()).isTrue();
        var json = mapper.readTree(result.message());
        assertThat(json.path("frames")).hasSize(2);
        assertThat(json.path("frames").get(0).path("path").asText()).isNotEmpty();
    }

    @Test
    void rejectsDisallowedHost() {
        FakeSession session = new FakeSession();
        List<AgentTool> tools = BrowserTools.all(new BrowserToolContext(
                () -> session, tempDir, List.of("bilibili.com")));
        ToolResult result = tool(tools, "browser_open").execute(call(Map.of(
                "url", "https://evil.example/")));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolContext call(Map<String, Object> args) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), args, AgentIdentity.external());
    }

    private static final class FakeSession implements BrowserSession {

        private String url = "about:blank";
        private String clicked;
        private String typed;

        @Override
        public void open(String url) {
            this.url = url;
        }

        @Override
        public String title() {
            return "bilibili";
        }

        @Override
        public String currentUrl() {
            return url;
        }

        @Override
        public List<ElementRef> snapshot() {
            return List.of(new ElementRef("bma3-1", "a", "视频", "https://www.bilibili.com/video",
                    "", 10, 20, 100, 30));
        }

        @Override
        public void click(String ref) {
            clicked = ref;
        }

        @Override
        public void type(String ref, String text) {
            typed = text;
        }

        @Override
        public String text() {
            return "bilibili page";
        }

        @Override
        public Path screenshot(Path directory) {
            try {
                Files.createDirectories(directory);
                Path path = directory.resolve("test.png");
                Files.writeString(path, "png");
                return path;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public double videoDuration() {
            return 10.0;
        }

        @Override
        public Path frameAt(double seconds, Path directory) {
            try {
                Files.createDirectories(directory);
                Path path = directory.resolve("frame-" + seconds + ".png");
                Files.writeString(path, "png");
                return path;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void close() {
        }
    }
}

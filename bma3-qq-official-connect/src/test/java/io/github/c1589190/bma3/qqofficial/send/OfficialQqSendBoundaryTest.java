package io.github.c1589190.bma3.qqofficial.send;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Architecture guard: no MCP tool reaches the low-level official write capability directly. */
class OfficialQqSendBoundaryTest {

    private static final Path OFFICIAL_SOURCE =
            Path.of("src/main/java/io/github/c1589190/bma3/qqofficial");

    @Test
    void lowLevelWriteMethodsAreNotPublic() throws Exception {
        assertThat(Modifier.isPublic(OfficialQqApiClient.class
                .getDeclaredMethod("sendGroupText", String.class, String.class).getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(OfficialQqApiClient.class
                .getDeclaredMethod("sendUserText", String.class, String.class).getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(OfficialQqApiClient.class.getDeclaredMethod(
                "sendGroupText", String.class, String.class, String.class, String.class,
                Integer.class).getModifiers())).isFalse();
    }

    @Test
    void onlyTheFixedTransportCallsTheWriteMethods() throws IOException {
        List<Path> callers;
        try (Stream<Path> files = Files.walk(OFFICIAL_SOURCE)) {
            callers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(OfficialQqSendBoundaryTest::mentionsWriteMethod)
                    .sorted()
                    .toList();
        }

        assertThat(callers).extracting(path -> path.getFileName().toString())
                .containsExactly("OfficialQqApiClient.java", "OfficialQqApiSendTransport.java");
    }

    @Test
    void toolsUseTheSendServiceAndNeverTheApiClient() throws IOException {
        try (Stream<Path> files = Files.walk(OFFICIAL_SOURCE.resolve("tools"))) {
            List<Path> toolSources = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertThat(toolSources).isNotEmpty();
            for (Path tool : toolSources) {
                String source = Files.readString(tool);
                assertThat(source).as(tool.toString()).doesNotContain("OfficialQqApiClient");
                assertThat(source).as(tool.toString()).doesNotContain("OfficialQqApiSendTransport");
                assertThat(source).as(tool.toString()).doesNotContain("OfficialQqSendTransport");
                assertThat(source).as(tool.toString()).doesNotContain("sendGroupText(");
                assertThat(source).as(tool.toString()).doesNotContain("sendUserText(");
                assertThat(source).as(tool.toString()).doesNotContain(".sendGroup(");
                assertThat(source).as(tool.toString()).doesNotContain(".sendUser(");
            }
        }
        assertThat(Files.readString(OFFICIAL_SOURCE.resolve(
                        "tools/QqOfficialSendTextTool.java")))
                .contains("OfficialQqSendService");
        assertThat(Files.readString(OFFICIAL_SOURCE.resolve(
                        "tools/QqOfficialReplyTool.java")))
                .contains("OfficialQqSendService");
    }

    @Test
    void onlyTheSendServiceInvokesTheTransportBoundary() throws IOException {
        List<Path> callers;
        try (Stream<Path> files = Files.walk(OFFICIAL_SOURCE)) {
            callers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(OfficialQqSendBoundaryTest::mentionsTransportInvocation)
                    .sorted()
                    .toList();
        }
        assertThat(callers).extracting(path -> path.getFileName().toString())
                .containsExactly("OfficialQqSendService.java");
    }

    private static boolean mentionsTransportInvocation(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains(".sendGroup(") || source.contains(".sendUser(");
        } catch (IOException failure) {
            throw new IllegalStateException("读取源码失败", failure);
        }
    }

    private static boolean mentionsWriteMethod(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("sendGroupText(") || source.contains("sendUserText(");
        } catch (IOException failure) {
            throw new IllegalStateException("读取源码失败", failure);
        }
    }
}

package io.mosire.bma3.qqofficial.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialQqMediaBoundaryTest {

    @Test
    void lowLevelUploadAndMediaSendMethodsAreNotPublic() throws Exception {
        assertThat(Modifier.isPublic(OfficialQqApiClient.class.getDeclaredMethod(
                "uploadGroupMedia", String.class, int.class, String.class, String.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(OfficialQqApiClient.class.getDeclaredMethod(
                "uploadUserMedia", String.class, int.class, String.class, String.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(OfficialQqApiClient.class.getDeclaredMethod(
                "sendGroupMedia", String.class, String.class, String.class, String.class,
                Integer.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(OfficialQqApiClient.class.getDeclaredMethod(
                "sendUserMedia", String.class, String.class, String.class, String.class,
                Integer.class).getModifiers())).isFalse();
    }

    @Test
    void onlyTheFixedMediaAdapterCallsTheNewClientMethods() throws Exception {
        Path sourceRoot = Path.of("src/main/java/io/mosire/bma3/qqofficial");
        List<Path> callers;
        try (var files = Files.walk(sourceRoot)) {
            callers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(OfficialQqMediaBoundaryTest::mentionsClientMediaMethod)
                    .sorted()
                    .toList();
        }
        assertThat(callers).extracting(path -> path.getFileName().toString())
                .containsExactly("OfficialQqMediaTransport.java");
    }

    @Test
    void mediaToolNamesAreNotRegisteredInAppSources() throws Exception {
        Path appSources = Path.of("../bma3-app/src/main/java");
        if (!Files.isDirectory(appSources)) {
            return;
        }
        try (var files = Files.walk(appSources)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java"))
                    .noneMatch(path -> contains(path, "qq_official_send_image")
                            || contains(path, "qq_official_send_file")))
                    .isTrue();
        }
    }

    private static boolean mentionsClientMediaMethod(Path path) {
        return contains(path, "api.uploadGroupMedia(")
                || contains(path, "api.uploadUserMedia(")
                || contains(path, "api.sendGroupMedia(")
                || contains(path, "api.sendUserMedia(");
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("读取源码失败", failure);
        }
    }
}

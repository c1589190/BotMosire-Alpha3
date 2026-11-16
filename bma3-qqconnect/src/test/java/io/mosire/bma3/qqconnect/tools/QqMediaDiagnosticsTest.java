package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqMediaDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    void snapshotIsContentFreeAndCountsAttemptsSuccessesAndFailures() throws Exception {
        Path dir = tempDir.resolve("downloads");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.bin"), "12345");
        QqMediaDiagnostics diagnostics = new QqMediaDiagnostics();
        diagnostics.attempt("image");
        diagnostics.success(5, true);
        diagnostics.attempt("record");
        diagnostics.failure("MEDIA_FETCH_FAILED");

        Map<String, Object> snapshot = diagnostics.snapshot(dir);

        assertThat(snapshot).containsEntry("attempts", 2L)
                .containsEntry("successes", 1L)
                .containsEntry("failures", 1L)
                .containsEntry("downloadedBytes", 5L)
                .containsEntry("splitImages", 1L);
        assertThat(snapshot.get("byKind").toString()).contains("image", "record");
        assertThat(snapshot.get("errors").toString()).contains("MEDIA_FETCH_FAILED");
        assertThat(snapshot.get("downloads").toString()).contains("files=1", "bytes=5");
        assertThat(snapshot.toString()).doesNotContain(dir.toAbsolutePath().toString());
    }

    @Test
    void factsFlattenCountersForWatchdogProbes() {
        QqMediaDiagnostics diagnostics = new QqMediaDiagnostics();
        diagnostics.attempt("image");
        diagnostics.success(12, false);
        diagnostics.failure("MEDIA_TOO_LARGE");

        assertThat(diagnostics.facts(tempDir))
                .containsEntry("attempts", "1")
                .containsEntry("successes", "1")
                .containsEntry("failures", "1")
                .containsEntry("downloadedBytes", "12");
    }
}

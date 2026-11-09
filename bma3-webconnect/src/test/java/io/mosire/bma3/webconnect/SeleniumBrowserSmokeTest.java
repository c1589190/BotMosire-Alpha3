package io.mosire.bma3.webconnect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in real browser smoke; skipped in normal unit runs. */
class SeleniumBrowserSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "BMA3_BROWSER_SMOKE", matches = "1")
    void opensBilibiliAndCapturesSnapshot() {
        Path tempDir = Path.of(System.getProperty("user.home"), ".cache", "bma3-selenium-smoke");
        BrowserOptions options = new BrowserOptions(
                "/snap/bin/chromium",
                true,
                tempDir.resolve("profile"),
                tempDir.resolve("shots"),
                List.of("bilibili.com"),
                Duration.ofSeconds(30));
        try (SeleniumBrowserSession session = new SeleniumBrowserSession(options)) {
            session.open("https://www.bilibili.com/");
            System.out.println("title=" + session.title());
            System.out.println("url=" + session.currentUrl());
            List<ElementRef> refs = session.snapshot();
            System.out.println("refs=" + refs.size());
            assertThat(session.currentUrl()).contains("bilibili.com");
            assertThat(refs).isNotEmpty();
            Path shot = session.screenshot(tempDir.resolve("shots"));
            assertThat(shot).exists();
            System.out.println("shot=" + shot);

            session.open("https://www.bilibili.com/video/BV1EPnG6DEJC");
            double duration = session.videoDuration();
            System.out.println("videoDuration=" + duration);
            if (duration > 0) {
                Path frame = session.frameAt(Math.min(5.0, duration / 2.0),
                        tempDir.resolve("video-shots"));
                assertThat(frame).exists();
                System.out.println("videoFrame=" + frame);
            }
        }
    }
}
